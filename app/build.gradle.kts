plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.karoopoi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.karoopoi"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/assets"))
}

// Rename output APKs to include app name, version, and build type.
// e.g. karoo-poi-1.1-debug.apk instead of app-debug.apk
android.applicationVariants.configureEach {
    val variantName = name
    outputs.configureEach {
        val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
        output.outputFileName = "karoo-poi-${android.defaultConfig.versionName}-$variantName.apk"
    }
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.8")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

tasks.register<Exec>("generatePoiDb") {
    group = "build"
    description = "Generate POI SQLite DB from OSM PBF"
    val pbfPath = project.findProperty("poi.pbf")?.toString() ?: "data/region.osm.pbf"
    val pbfFile = rootProject.file(pbfPath)
    val outputDb = layout.buildDirectory.file("generated/assets/pois.db").get().asFile

    // Register input only when the PBF exists so Gradle can skip when unchanged.
    // When the PBF is absent, no input is registered and the task always runs,
    // hitting the doFirst guard below which fails the build with a clear message.
    if (pbfFile.exists()) {
        inputs.file(pbfFile)
    }
    outputs.file(outputDb)

    doFirst {
        outputDb.parentFile.mkdirs()
        if (!pbfFile.exists()) {
            throw GradleException(
                "POI database source PBF not found at: ${pbfFile.absolutePath}\n" +
                "The POI database is generated at build time from an OSM PBF extract.\n" +
                "Download a region from Geofabrik, e.g.:\n" +
                "  mkdir -p data && wget https://download.geofabrik.de/europe/finland-latest.osm.pbf -O data/region.osm.pbf\n" +
                "Or specify a custom path: ./gradlew app:assembleDebug -Ppoi.pbf=/path/to/region.osm.pbf"
            )
        }
    }
    commandLine("python3", rootProject.file("build_scripts/poi_pipeline.py").absolutePath, "--pbf", pbfFile.absolutePath, "--output", outputDb.absolutePath)
}

afterEvaluate {
    // generatePoiDb produces the only pois.db asset, so every task that
    // consumes assets (merge, lint-vital) must depend on it.
    listOf(
        "mergeReleaseAssets",
        "mergeDebugAssets",
        "lintVitalAnalyzeRelease",
        "generateReleaseLintVitalReportModel",
    ).forEach { taskName ->
        tasks.findByName(taskName)?.dependsOn("generatePoiDb")
    }
}

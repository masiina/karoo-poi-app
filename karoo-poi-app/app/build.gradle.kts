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
        versionCode = 2
        versionName = "1.1"
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
    val pbfFile = project.findProperty("poi.pbf")?.toString() ?: "data/region.osm.pbf"
    val outputDb = layout.buildDirectory.file("generated/assets/pois.db").get().asFile
    inputs.file(file(pbfFile))
    outputs.file(outputDb)
    doFirst { outputDb.parentFile.mkdirs() }
    commandLine("python3", rootProject.file("build_scripts/poi_pipeline.py").absolutePath, "--pbf", file(pbfFile).absolutePath, "--output", outputDb.absolutePath)
    onlyIf { file(pbfFile).exists() }
}

afterEvaluate {
    tasks.named("mergeReleaseAssets") { dependsOn("generatePoiDb") }
    tasks.named("mergeDebugAssets") { dependsOn("generatePoiDb") }
}

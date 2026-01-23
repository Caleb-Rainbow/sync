import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("maven-publish")
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

extensions.configure<LibraryExtension>("android") {
    namespace = "com.util.sync"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.directories.add("src/main/java")
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.github.Caleb-Rainbow"
            artifactId = "sync"
            version = "1.0.8"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    //雪花id生成
    api(libs.idgenerator)
    //work
    api(libs.androidx.work)
    api(libs.network)
}
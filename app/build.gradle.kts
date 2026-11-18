plugins {
    id("com.android.application")
}

android {
    namespace = "com.mylivestreamer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mylivestreamer.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
    }

    // One fixed key kept in the repo so every build is signed the same way
    // and a new APK installs over the old one (settings are kept).
    signingConfigs {
        create("mls") {
            storeFile = file("mls-release.jks")
            storePassword = "mylivestreamer"
            keyAlias = "mls"
            keyPassword = "mylivestreamer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mls")
        }
        debug {
            signingConfig = signingConfigs.getByName("mls")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.2")
    implementation("com.google.mlkit:translate:17.0.3")
}

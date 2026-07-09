plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.launchRedirector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.launchRedirector"
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    compileOnly(files("libs/xposed-api-89.jar"))
}

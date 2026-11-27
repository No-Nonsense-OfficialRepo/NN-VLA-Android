plugins { id("com.android.application") }

android {
    namespace = "com.nononsense.vla"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.nononsense.vla"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "0.3.4"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":zerotier-core"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
}


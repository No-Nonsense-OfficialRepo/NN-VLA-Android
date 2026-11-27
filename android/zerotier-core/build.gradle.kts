plugins {
    id("com.android.library")
}

android {
    namespace = "com.zerotier.sdk"
    compileSdk = 37
    enableKotlin = false
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    sourceSets {
        named("main") {
            java.srcDir("../third_party/ZeroTierOne/java/src")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

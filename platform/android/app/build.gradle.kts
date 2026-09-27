plugins {
    id("com.android.library")
}

android {
    namespace = "com.potatotv.pacc.android"
    compileSdk = 35

    // README 约定 NDK 26+：native_probe.cpp 用到 /proc/self/task 遍历与 ptrace，
    // 依赖 r26 起的统一 sysroot。版本号需与本机/CI 已安装的 NDK 一致。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 23
        // 只随 arm64 与 x86_64 分发：armeabi-v7a 已非主流机型，多一份 ABI 只增包体。
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        // 库自身的 keep 规则随 AAR 传递给宿主 App，避免宿主漏配导致 JNI 符号找不到
        consumerProguardFiles("proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

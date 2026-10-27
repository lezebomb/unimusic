plugins {
    id("build-logic.android.library")
}

android {
    namespace = "moe.ouom.neriplayer.nativebridge"
    ndkVersion = Version.getNdkVersion()

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-fexceptions", "-frtti")
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

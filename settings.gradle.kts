import kotlin.io.path.Path

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral() // local build workaround: try central first (dl.google.com rate-limited)
        gradlePluginPortal()
        google()
        mavenLocal()
        maven("https://jitpack.io")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral() // local build workaround: try central first (dl.google.com rate-limited)
        google()
        mavenLocal()
        maven("https://jitpack.io")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        maven("https://maven.aliyun.com/repository/public")
    }

    versionCatalogs {
        create("androidvmtools") {
            from(files(
                Path(rootDir.path, "external", "AndroidVMTools", "gradle", "libs.versions.toml")))
        }
    }
}

rootProject.name = "ApkeSU"

include(
    ":app",
    ":common",
    ":stub",
    ":zygote",
)

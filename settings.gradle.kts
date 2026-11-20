pluginManagement {
    repositories {
        // dl.google.com 本机不可达，AGP/aapt2 走阿里云 google 镜像
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "tvrc"

include(":core")
include(":app")

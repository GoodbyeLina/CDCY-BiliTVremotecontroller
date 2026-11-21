// 三代原生 App —— 根构建脚本
// 说明：Kotlin 插件版本在各子模块的 plugins 块内声明，这里不再往 buildscript 塞 classpath
//（之前 buildscript 与 plugins 双声明会让 Gradle 报 "already on the classpath with unknown version"）。

allprojects {
    repositories {
        // dl.google.com 本机不可达；镜像必须排在前面，否则解析会先超时再回退
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

tasks.register("cleanAll") {
    doLast {
        println("no-op: use 'gradle clean'")
    }
}

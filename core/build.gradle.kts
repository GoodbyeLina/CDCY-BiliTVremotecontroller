plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.register<JavaExec>("runProxy") {
    group = "application"
    description = "启动本地媒体代理（P2-4 验收用，前台阻塞）"
    mainClass.set("tvrc.core.tools.ProxyMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runConfigCheck") {
    group = "application"
    description = "校验配置文件（P4-1 verify-on-tv.sh 复用同一个 ConfigValidator）"
    mainClass.set("tvrc.core.tools.ConfigCheckMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

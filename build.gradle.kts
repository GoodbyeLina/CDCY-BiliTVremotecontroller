buildscript {
    repositories {
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

allprojects {
    repositories {
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
    }
}

tasks.register("cleanAll") {
    doLast {
        println("no-op: use 'gradle clean'")
    }
}

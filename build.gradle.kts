plugins {
    java
    id("com.gradleup.shadow") version "8.3.0" apply false
}

subprojects {
    apply(plugin = "java")
    group = "com.campfire"
    version = "1.0.0"
    java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
    repositories {
        mavenCentral()
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

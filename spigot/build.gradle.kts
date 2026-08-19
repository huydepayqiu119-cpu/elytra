plugins {
    id("com.gradleup.shadow") version "8.3.0"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("ElytraSpigot")
    minimize()
}
tasks.build { dependsOn(tasks.shadowJar) }

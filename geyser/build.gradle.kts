plugins {
    id("com.gradleup.shadow") version "8.3.0"
}

dependencies {
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("ElytraGeyser")
    minimize()
}
tasks.build { dependsOn(tasks.shadowJar) }

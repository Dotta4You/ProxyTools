plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
}

val relocationBase = "de.doetchen.projects.proxytools.libs"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // Velocity API
}

dependencies {
    // Proxy APIs (provided by the proxy at runtime). Oldest supported versions, so the jar runs on newer ones as well.
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    compileOnly("com.velocitypowered:velocity-api:3.4.0")

    // Bundled into the jar (relocated below)
    implementation("org.yaml:snakeyaml:2.7")
    implementation("org.bstats:bstats-bungeecord:3.2.1")
    implementation("org.bstats:bstats-velocity:3.2.1")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    // Java 17 is the lowest common denominator of all supported proxy versions
    jvmToolchain(17)
}

tasks {
    processResources {
        val props = mapOf("version" to project.version, "description" to project.description)
        filesMatching(listOf("bungee.yml", "velocity-plugin.json")) {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        archiveClassifier = ""
        archiveFileName = "${rootProject.name}-${project.version}.jar"
        exclude("META-INF/*.kotlin_module")
        relocate("kotlin", "$relocationBase.kotlin")
        relocate("org.jetbrains", "$relocationBase.jetbrains")
        relocate("org.intellij", "$relocationBase.intellij")
        relocate("org.yaml.snakeyaml", "$relocationBase.snakeyaml")
        // bStats explicitly requires relocating its package, so multiple plugins bundling it don't clash.
        relocate("org.bstats", "$relocationBase.bstats")
    }

    jar {
        // Only the shaded jar is published
        archiveClassifier = "plain"
    }

    build {
        dependsOn(shadowJar)
    }
}

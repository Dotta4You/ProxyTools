plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
}

val relocationBase = "de.doetchen.projects.proxytools.libs"
val bstatsVersion = "3.2.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    compileOnly("com.velocitypowered:velocity-api:3.4.0")

    implementation("org.yaml:snakeyaml:2.7")
    implementation("org.bstats:bstats-bungeecord:$bstatsVersion")
    implementation("org.bstats:bstats-velocity:$bstatsVersion")
    implementation("com.h2database:h2:2.5.252")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.10")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
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
        mergeServiceFiles()
        manifest { attributes("Multi-Release" to "true") }
        relocate("kotlin", "$relocationBase.kotlin")
        relocate("org.jetbrains", "$relocationBase.jetbrains")
        relocate("org.intellij", "$relocationBase.intellij")
        relocate("org.yaml.snakeyaml", "$relocationBase.snakeyaml")
        relocate("org.bstats", "$relocationBase.bstats")
        relocate("org.h2", "$relocationBase.h2") {
            exclude("org/h2/res/**")
        }
        relocate("org.mariadb.jdbc", "$relocationBase.mariadb")
    }

    jar {
        archiveClassifier = "plain"
    }

    build {
        dependsOn(shadowJar)
    }
}

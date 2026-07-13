plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.3.0"
}

group = "anon.def9a2a4"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("org.bstats:bstats-bukkit:3.1.0")
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    // Keep the thin jar (subprojects compile against it via `compileOnly(project(":"))`), but give it a
    // `-plain` classifier so it doesn't collide with the shadow jar and the deploy steps can skip it.
    // Shipping BOTH jars to the server makes Paper report "Ambiguous plugin name 'DefCoreLib'", so the
    // Makefile copies only the classifier-less shadow jar into the plugins folder. shadowJar is the only
    // deployable artifact.
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        relocate("org.bstats", "anon.def9a2a4.bstats")
        mergeServiceFiles()
        archiveClassifier.set("")
        archiveBaseName.set("defCoreLib")
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/def9a2a4/defCoreLib")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

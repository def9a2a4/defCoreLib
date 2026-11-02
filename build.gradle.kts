plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.3.0"
}

group = "anon.def9a2a4"
version = "0.5.0"

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
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.bstats:bstats-bukkit:3.1.0")

    // Unit tests cover the Bukkit-free logic (state files, rotation math). paper-api is on the test
    // classpath for its value types (YamlConfiguration, BlockFace) — no server is started.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test {
        useJUnitPlatform()
        // Some tests read these from the repo, not the classpath: declare them so editing only them
        // re-runs the test, and pass the root so the test doesn't depend on the working directory.
        inputs.file(".github/workflows/server-test.yml")
        inputs.file("mech/src/main/resources/mech_datapack/pack.mcmeta")
        inputs.file("pipes/src/main/resources/pipes.yml")
        inputs.file("pipes/src/main/resources/config.yml")
        systemProperty("repoRoot", rootDir.absolutePath)
    }

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

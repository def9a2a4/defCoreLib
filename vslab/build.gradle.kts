plugins {
    java
    id("com.gradleup.shadow") version "9.3.0"
}

group = "anon.def9a2a4"
version = "0.3.0"

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
    // The core DefCoreLib runtime (installed separately at runtime). Consume the root's `shadow`
    // configuration so the dependency on the shadowJar task is explicit (avoids Gradle's
    // implicit-dependency error from jar/shadowJar sharing an output name).
    compileOnly(project(path = ":", configuration = "shadow"))
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

    shadowJar {
        relocate("org.bstats", "anon.def9a2a4.bstats")
        mergeServiceFiles()
        archiveClassifier.set("")
        archiveBaseName.set("vslab")
    }
}

plugins { java }

group = "dev.knightsmp"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

// Flat layout: every file sits in the repository root (easy to upload from a phone).
sourceSets {
    main {
        java { setSrcDirs(listOf(".")); include("*.java") }
        resources { setSrcDirs(listOf(".")); include("*.yml") }
    }
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar { archiveFileName.set("ModsForKnightSMP-${project.version}.jar") }

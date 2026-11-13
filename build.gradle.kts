plugins { java }

group = "dev.knightsmp"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.3 (new version scheme: <mcver>.build.<n>). Use 26.3.build.+ once 26.3 is published in Maven.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar { archiveFileName.set("ModsForKnightSMP-${project.version}.jar") }

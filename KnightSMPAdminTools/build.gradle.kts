plugins {
    java
}

group = "net.knightsmp"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API only. SQLite JDBC ships inside the Paper server, so nothing is shaded.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    jar {
        archiveFileName.set("KnightSMPAdminTools.jar")
    }
}

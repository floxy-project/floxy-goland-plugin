plugins {
    id("org.jetbrains.intellij") version "1.17.1"
    kotlin("jvm") version "2.2.0"
}

group = "com.floxy"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    // GoLand 2025.2
    type.set("GO")
    version.set("2025.2")

    plugins.set(listOf("org.jetbrains.plugins.go"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("252.0")
        untilBuild.set("253.*")
    }

    buildPlugin {
        archiveFileName.set("floxy-goland-plugin.zip")
    }

    runIde {
        ideDir.set(file("/Users/roman/Applications/GoLand.app"))
    }

    buildSearchableOptions {
        enabled = false
    }
}

dependencies {
    implementation("net.sourceforge.plantuml:plantuml:1.2024.5")
}

plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    defaultIdeProduct = IntelliJPlatform.GoLand("2025.2")
    pluginConfiguration {
        name = "Floxy Flow Viewer"
        id = "com.floxy.flow.viewer"
        version = "0.1.0"
        vendor {
            name = "Floxy"
            url = "https://github.com/rom8726/floxy-idea-plugin"
        }
        ideaVersion {
            sinceBuild.set("252.0")
            untilBuild.set("252.*")
        }
        description = "Visualize and validate floxy.Builder workflows in GoLand."
    }

    plugins {
        bundled("com.intellij.java")
        bundled("org.jetbrains.plugins.go")
        // optional: diagram APIs are available in platform
    }
}

dependencies {
    intellijPlatform {
        /* Use GoLand platform dependencies */
        goLand("2025.2")
        bundledPlugin("org.jetbrains.plugins.go")
    }
    implementation("net.sourceforge.plantuml:plantuml:1.2024.7")
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        java.srcDirs("src/main/kotlin")
        resources.srcDirs("src/main/resources")
    }
}

plugins {
    id("org.jetbrains.intellij") version "1.17.1"
    kotlin("jvm") version "2.4.0"
}

// Prevent IntelliJ Gradle plugin from trying to resolve a real IDE during unit tests
val isTestTask = gradle.startParameter.taskNames.any { it.contains("test", ignoreCase = true) }
if (isTestTask) {
    System.setProperty("idea.home.path", project.layout.projectDirectory.dir(".fake-ide").asFile.absolutePath)
}

group = "com.floxy"
version = "0.3.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellij {
    // GoLand 2026.2
    type.set("GO")
    version.set("2026.2")

    plugins.set(listOf("org.jetbrains.plugins.go"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("262.0")
        untilBuild.set("262.*")
    }

    buildPlugin {
        archiveFileName.set("floxy-goland-plugin.zip")
    }

    runIde {
        val userHome = System.getProperty("user.home")
        val macIdeContents = file("$userHome/Applications/GoLand.app/Contents")
        if (macIdeContents.exists()) {
            ideDir.set(macIdeContents)
        }
    }

    buildSearchableOptions {
        enabled = false
    }

    test {
        useJUnitPlatform()
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

afterEvaluate {
    tasks.withType<Test>().configureEach {
        jvmArgumentProviders.clear()
        systemProperties.remove("java.system.class.loader")
        jvmArgs("--add-exports=java.base/sun.nio.fs=ALL-UNNAMED")
    }
}

dependencies {
    implementation("net.sourceforge.plantuml:plantuml:1.2024.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-console:1.11.3")
}

tasks.register<JavaExec>("unitTest") {
    group = "verification"
    description = "Runs unit tests via JUnit ConsoleLauncher"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args("--scan-classpath")
}

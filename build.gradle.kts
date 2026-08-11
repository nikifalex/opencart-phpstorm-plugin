import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

/**
 * The path to an installed PhpStorm comes from local.properties, which is not under version control.
 * Without it — on CI, for instance — the platform is downloaded from the JetBrains repository.
 */
val phpstormLocalPath: String? = run {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return@run null
    val properties = Properties().apply { file.inputStream().use { load(it) } }
    properties.getProperty("phpstormLocalPath")?.takeIf { it.isNotBlank() && File(it).isDirectory }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (phpstormLocalPath != null) {
            local(phpstormLocalPath)
        } else {
            phpstorm(providers.gradleProperty("phpstormVersion").get())
        }
        bundledPlugins("com.jetbrains.php", "com.jetbrains.twig")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = provider {
            val changelog = rootProject.file("CHANGELOG.md")
            if (!changelog.exists()) {
                ""
            } else {
                // Only the latest changelog section goes into the plugin description.
                changelog.readText()
                    .substringAfter("## ")
                    .substringBefore("\n## ")
                    .lineSequence()
                    .drop(1)
                    .joinToString("<br/>")
            }
        }
    }
    buildSearchableOptions = false
}

tasks {
    wrapper {
        gradleVersion = "9.7.0"
    }
}

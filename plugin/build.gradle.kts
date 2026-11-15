plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        local(providers.gradleProperty("androidStudioPath"))
        bundledPlugin("org.jetbrains.android")
    }
}

// Android Studio 2026.x platform classes are compiled for Java 25.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}

// The IDE ships the Kotlin stdlib; bundling a second copy breaks class loading.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

tasks.jar {
    from(files(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
}

// ./gradlew :plugin:runIde -PopenProject=/path/to/android/project
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    providers.gradleProperty("openProject").orNull?.let { args(it) }
}

// Signing material lives outside the repo (default ~/.designimporter-signing); env vars override it on CI.
val signingDir = providers.gradleProperty("signingDir").orElse(System.getProperty("user.home") + "/.designimporter-signing")

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = "com.abdelfattah.ahmed.designimporter"
        name = "Claude DesignKit for Compose"
        version = project.version.toString()
        vendor {
            name = "Ahmed Abdelfattah"
        }
        // Compiled for Java 25: Android Studio 2026.2 (build 262) is the first that runs on it.
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
              <li>Import a Claude Design export (zip or folder) into an Android module as a Jetpack Compose design system.</li>
              <li>Theme: light/dark colors, type scale, shapes, spacing, elevation and sizes, with the design's usage notes as KDoc.</li>
              <li>Icons: Material Symbols used by the design map to Compose Material icons; custom SVGs become ImageVector or vector drawables.</li>
              <li>Components: button, text field, card, chip and status badge variants from the design's CSS.</li>
              <li>Localization: English and Arabic strings merged into values/strings.xml and values-ar/strings.xml.</li>
              <li>Safe re-imports: only generated files and the generated strings block are touched.</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            local(providers.gradleProperty("androidStudioPath"))
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
            .orElse(signingDir.map { File(it, "chain.crt").takeIf(File::exists)?.readText() ?: "" })
        privateKey = providers.environmentVariable("PRIVATE_KEY")
            .orElse(signingDir.map { File(it, "private.pem").takeIf(File::exists)?.readText() ?: "" })
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD").orElse("")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

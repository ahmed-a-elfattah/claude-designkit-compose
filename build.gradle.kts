plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jetbrains.intellij.platform") version "2.20.0" apply false
}

subprojects {
    group = "dev.designimporter"
    version = "1.0.1"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

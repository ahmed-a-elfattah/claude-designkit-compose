plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.13.1")
    // Same icon set as androidx.compose.material:material-icons-* 1.7.x, published for the JVM.
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
}

val indexFile = rootProject.file("core/src/main/resources/dev/designimporter/core/material-icons-index.json")

tasks.register<JavaExec>("buildMaterialIndex") {
    group = "design importer"
    description = "Regenerates the Material icon geometry index bundled in :core."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.designimporter.tools.MaterialIndexBuilderKt")
    args(indexFile.absolutePath)
}

/** ./gradlew :tools:generate -Pexport=<zip|folder> -Pmodule=<android module dir> -Ppackage=<pkg> [-Pnamespace=<R pkg>] [-PiconFormat=DRAWABLE] */
tasks.register<JavaExec>("generate") {
    group = "design importer"
    description = "Imports a Claude Design export into an Android module without the IDE."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.designimporter.tools.GenerateCliKt")
    args(
        listOfNotNull(
            providers.gradleProperty("export").orNull,
            providers.gradleProperty("module").orNull,
            providers.gradleProperty("package").orNull,
            providers.gradleProperty("namespace").orNull ?: "",
            providers.gradleProperty("iconFormat").orNull ?: "IMAGE_VECTOR",
        )
    )
}

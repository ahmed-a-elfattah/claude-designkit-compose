plugins {
    kotlin("jvm")
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

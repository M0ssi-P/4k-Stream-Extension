// tests only: the plugin jar itself still holds just our classes (see the root build)
dependencies {
    "testImplementation"(kotlin("test", providers.gradleProperty("kotlinVersion").get()))
    "testImplementation"(providers.gradleProperty("fourkApi").get())
}
tasks.named<Test>("test") { useJUnitPlatform() }

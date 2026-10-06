plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    // Only used by the built-in strategy -> Strategy DNA adapter. The dependency
    // is one-way: core:strategy / strategy-engine know nothing about intelligence.
    implementation(project(":core:strategy"))
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

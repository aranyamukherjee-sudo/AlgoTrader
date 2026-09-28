plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":core:domain"))
    implementation(project(":core:strategy"))
    implementation(project(":core:marketdata"))
}

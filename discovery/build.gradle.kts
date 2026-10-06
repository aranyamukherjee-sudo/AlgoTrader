plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:strategy"))
    implementation(project(":core:intelligence"))
    // Reused as-is: BacktestEngine and PerformanceMetrics do all trade/P&L math.
    implementation(project(":backtest"))
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

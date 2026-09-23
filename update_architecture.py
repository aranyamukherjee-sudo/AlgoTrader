from pathlib import Path

files = {
    "core/strategy/build.gradle.kts": """plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
}
""",

    "core/execution/build.gradle.kts": """plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
}
""",

    "core/marketdata/build.gradle.kts": """plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
}
""",

    "strategy-engine/build.gradle.kts": """plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:strategy"))
    implementation(project(":core:marketdata"))
}
""",

    "backtest/build.gradle.kts": """plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:strategy"))
    implementation(project(":core:marketdata"))
    implementation(project(":core:execution"))
    implementation(project(":strategy-engine"))
}
""",

    "data/build.gradle.kts": """plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:marketdata"))
}
""",
}

# Update app separately, preserving its existing Android configuration.
app = Path("app/build.gradle.kts")
text = app.read_text()

dependencies = """
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:strategy"))
    implementation(project(":core:execution"))
    implementation(project(":core:marketdata"))
    implementation(project(":strategy-engine"))
    implementation(project(":backtest"))
    implementation(project(":data"))
}
"""

if "dependencies {" not in text:
    text = text.rstrip() + "\n" + dependencies
    app.write_text(text)
else:
    print("WARNING: app/build.gradle.kts already contains dependencies; leaving it unchanged.")

for filename, content in files.items():
    path = Path(filename)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)
    print(f"Updated: {filename}")

print("Architecture Gradle files updated.")

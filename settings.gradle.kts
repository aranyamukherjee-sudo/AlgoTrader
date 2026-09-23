pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AlgoTrader"

include(":app")
include(":core:domain")
include(":core:strategy")
include(":core:execution")
include(":core:marketdata")
include(":strategy-engine")
include(":backtest")
include(":data")

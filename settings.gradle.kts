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

rootProject.name = "GE360"

include(":app")
include(":core:common")
include(":core:backup")
include(":core:bridge")
include(":feature:home")
include(":feature:clienti")
include(":feature:cantieri")
include(":feature:centrocantiere")
include(":feature:giornata")
include(":feature:messaggi")
include(":feature:spese")
include(":feature:scanner")
include(":feature:preventivi")
include(":feature:planimetrie")
include(":feature:attrezzi")
include(":feature:galleria")
include(":feature:fisco")
include(":feature:backup")
include(":feature:marketing")
include(":feature:jarvis")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal {
            content {
                excludeGroup("org.jetbrains.kotlin")
                excludeGroup("org.jetbrains.kotlin.android")
                excludeGroup("org.jetbrains.kotlin.plugin.compose")
                excludeGroup("com.android.application")
                excludeGroup("com.android.tools.build")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LibrePocket"
include(":app")

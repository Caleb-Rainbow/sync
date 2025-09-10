pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "SyncMoudle"
include(":app")
/*includeBuild("F:\\CodeProject\\AndroidComposeProject\\NetworkMoudle"){
    dependencySubstitution {
        // 这里的 :Ktor-Network 是指库项目中 rootProject.name
        substitute(module("com.github.Caleb-Rainbow:Ktor-Network"))
            .using(project(":ktor"))
    }
}*/
include(":sync")

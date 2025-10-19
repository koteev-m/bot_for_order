rootProject.name = "tg-shop-monorepo"

include(
    ":app",
    ":domain",
    ":infra:db",
    ":infra:redis",
    ":bots",
    ":miniapp",
)

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        ivy {
            name = "Node.js"
            url = uri("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")
                artifact("v[revision]/[artifact]-v[revision].[ext]")
            }
            metadataSources { artifact() }
        }
        ivy {
            name = "Yarn"
            url = uri("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision]-[classifier].[ext]")
                artifact("v[revision]/[artifact]-v[revision].[ext]")
            }
            metadataSources { artifact() }
        }
    }
}

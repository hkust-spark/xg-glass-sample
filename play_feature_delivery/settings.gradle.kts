val metaGithubToken = (
    providers.gradleProperty("github_token").orNull
        ?: providers.environmentVariable("GITHUB_TOKEN").orNull
        ?: ""
).trim()

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
        if (metaGithubToken.isNotEmpty()) {
            exclusiveContent {
                forRepository {
                    maven {
                        url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                        credentials {
                            username = providers.gradleProperty("github_user").orNull
                                ?: providers.environmentVariable("GITHUB_ACTOR").orNull
                                ?: ""
                            password = metaGithubToken
                        }
                    }
                }
                filter {
                    includeGroupByRegex("com\\.meta\\.wearable(\\..+)?")
                }
            }
        } else {
            println("Warning: Meta DAT artifacts require github_token or GITHUB_TOKEN with read:packages.")
        }
    }
}

rootProject.name = "xgglass-play-feature-delivery-sample"

include(":app")
include(":feature:meta")

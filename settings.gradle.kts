rootProject.name = "chudpresence-revanced-patches"

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/revanced/registry")
            credentials {
                username = providers.gradleProperty("githubPackagesUsername").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("githubPackagesPassword").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.11"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
    }
}

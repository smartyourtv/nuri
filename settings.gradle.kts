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
        // Local fallback LAST (bug-hunt fix NU-6, 2026-10-16): the local repo
        // built from cached artifacts (see AGENTS.md build lessons) contains
        // some POM-only entries (e.g. slf4j-api 1.7.30 — POM present, JAR
        // absent). When it was listed FIRST, Gradle took the module metadata
        // from it and then failed hard looking for the missing JAR there
        // instead of falling through to Maven Central. Listed last, it only
        // serves modules the remote repos don't have.
        val localRepo = java.io.File("/home/hatch/workspace/build-support/local-maven-repo")
        if (localRepo.exists()) {
            maven { url = localRepo.toURI() }
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

rootProject.name = "Nuri"
include(":app")

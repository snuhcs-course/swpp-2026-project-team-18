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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 카카오 로그인 SDK(todo §2 소셜 로그인). 카카오 패키지만 여기서 받는다 — 다른 의존이
        // 이 저장소로 새지 않게 한다.
        maven {
            url = uri("https://devrepo.kakao.com/nexus/content/groups/public/")
            content { includeGroup("com.kakao.sdk") }
        }
    }
}

rootProject.name = "APP"
include(":app")

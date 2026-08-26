import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val consumerRepo = providers.gradleProperty("issue731ConsumerRepo")
        .orElse(providers.environmentVariable("ISSUE731_CONSUMER_REPO"))
        .get()
    repositories {
        maven { url = uri(consumerRepo) }
        mavenCentral()
    }
}
rootProject.name = "issue731-legacy-binary-runtime"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.ejudge"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Builds against CLion downloaded from JetBrains by default. To use an installed IDE instead:
//   ./gradlew buildPlugin -PlocalIde=/Applications/CLion.app
val localIde = providers.gradleProperty("localIde").orNull

dependencies {
    implementation("org.jsoup:jsoup:1.21.2")
    intellijPlatform {
        if (localIde != null) local(localIde) else clion("2026.2")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

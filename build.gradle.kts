// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

spotless {
    kotlin {
        target("app/src/**/*.kt", "core/*/src/**/*.kt")
        targetExclude("app/src/prototype/**", "**/build/**")
        ktlint("1.8.0").setEditorConfigPath(rootProject.file(".editorconfig"))
        // Сохраняем переносы ktlint, но не блокируем неразбиваемые строки.
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:max-line-length"
        }
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "core/*/*.gradle.kts")
        ktlint("1.8.0").setEditorConfigPath(rootProject.file(".editorconfig"))
        // Сохраняем переносы ktlint, но не блокируем неразбиваемые строки.
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:max-line-length"
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("spotlessCheck"))
    }
}

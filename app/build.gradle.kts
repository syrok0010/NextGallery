plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.syrok0010.nextgallery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.syrok0010.nextgallery"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        val debug = getByName("debug")

        create("automation") {
            initWith(debug)
            applicationIdSuffix = ".automation"
            versionNameSuffix = "-automation"
            matchingFallbacks += "debug"
        }

        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testBuildType = "automation"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:media"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.telephoto.zoomable.image.coil3)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    add("automationImplementation", libs.androidx.compose.ui.test.manifest)
    add("automationImplementation", libs.androidx.compose.ui.tooling)
}

// Package boundaries are checked until individual features justify separate Gradle projects.
val verifyFeatureBoundaries by tasks.registering {
    group = "verification"
    description = "Checks ownership of core, feature and app dependencies."
    val sources = fileTree("src/main/java") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val prefix = "com.syrok0010.nextgallery."
        val importPattern = Regex("(?m)^import (com\\.syrok0010\\.nextgallery\\.[\\w.]+)")
        sources.forEach { source ->
            val text = source.readText()
            val owner = Regex("(?m)^package (.+)").find(text)!!.groupValues[1]
            importPattern.findAll(text).forEach { match ->
                val dependency = match.groupValues[1]
                check(!(owner.startsWith(prefix + "core.") &&
                    (dependency.startsWith(prefix + "feature.") || dependency.startsWith(prefix + "app.")))) {
                    "${source.name}: core must not depend on $dependency"
                }
                check(!(owner.startsWith(prefix + "feature.") && dependency.startsWith(prefix + "app."))) {
                    "${source.name}: app composes features, not the reverse ($dependency)"
                }
                check(!(owner.startsWith(prefix + "feature.viewer") && dependency.startsWith(prefix + "feature.timeline"))) {
                    "${source.name}: viewer accepts media sequence, not timeline internals"
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyFeatureBoundaries) }

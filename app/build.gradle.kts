import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.andrewchqng.usbwebcam"
    defaultConfig {
        applicationId = "com.andrewchqng.usbwebcam"

        // SDK support
        minSdk = 26
        targetSdk = 35
        compileSdk = 36

        // App Versioning
        versionCode = 325
        versionName = "v3.2.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Suppressing warning because this has been marked Incubating for a long long time now.
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    val releaseStoreFile = keystoreProps.getProperty("storeFile")
        ?: providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    val releaseStorePassword = keystoreProps.getProperty("storePassword")
        ?: providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = keystoreProps.getProperty("keyAlias")
        ?: providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
    val releaseKeyPassword = keystoreProps.getProperty("keyPassword")
        ?: providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    // Build configuration
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs.useLegacyPackaging = true
    }
    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Disable Google-encrypted binary blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    // Java version
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkAllWarnings = true

        baseline = file("lint-baseline.xml")

        // I used to have this disabled, but I shouldn't. If an error is unjustified, then I should knock its severity
        // down to "warning" or disable it. Appropriate lint errors should cause a failure.
        //abortOnError = false
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        val minSDK: Int = defaultConfig.minSdk!!
        val targetSDK: Int = defaultConfig.targetSdk!!
        managedDevices {
            animationsDisabled = true
            localDevices {
                val testDevices = arrayOf("Pixel 5")
                val apiLevelsMissingATD = setOf(26, 27, 28, 29, 34, 35)
                val testPrimarySystemImage = "aosp-atd"
                val testFallbackSystemImage = "aosp"
                for (thisDevice in testDevices) {
                    for (thisApiLevel in minSDK..targetSDK) {
                        // lowercase and remove spaces
                        val adjustedDeviceName = thisDevice.lowercase().replace(" ", "")
                        val name = "${adjustedDeviceName}_${thisApiLevel}"
                        create(name) {
                            device = thisDevice
                            apiLevel = thisApiLevel
                            // Some API levels don't provide my preferred system image, so use the fallback in those cases
                            systemImageSource = if (thisApiLevel in apiLevelsMissingATD) {
                                testFallbackSystemImage
                            } else {
                                testPrimarySystemImage
                            }
                        }
                    }
                }
            }
            groups {
                // NOTE: Testing too many API levels in CI causes it to run out of storage, I could work
                //       around it, but that's something for later.
                create("ci") {
                    val apiLevelsToTestInCI = arrayOf(29, 30, 32, targetSDK)
                    for (device in localDevices) {
                        if (device.apiLevel in apiLevelsToTestInCI) {
                            targetDevices.add(device)
                        }
                    }
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")

        freeCompilerArgs = listOf(
            "-Xannotation-default-target=param-property",
            "-Xjspecify-annotations=strict",
            "-Xtype-enhancement-improvements-strict-mode"
        )
    }
}

dependencies {
    // Apply BOM to the different configs, suppressing warnings because they're not true
    val composeBom = platform(libs.androidx.compose.bom)
    @Suppress("AvoidDuplicateDependencies")
    implementation(composeBom)
    @Suppress("AvoidDuplicateDependencies")
    testImplementation(composeBom)
    @Suppress("AvoidDuplicateDependencies")
    androidTestImplementation(composeBom)

    implementation(libs.android.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference.ktx)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.viewbinding)
    implementation(libs.androidx.compose.animation.core)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.unit)

    // Logging
    implementation(libs.timber)

    // Root helper library
    implementation(libs.libsu.core)

    // Navigation
    implementation(libs.voyager.navigator)
    implementation(libs.voyager.transitions)
    implementation(libs.voyager.core)

    // Android Studio Preview support
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit Testing
    testImplementation(libs.junit)

    // Instrumented Testing
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.tracing.ktx)
}

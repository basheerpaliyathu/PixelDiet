plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.pixeldiet.compressor"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // No native code is bundled by design (the no-.so guarantee). CI verifies this.

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = (findProperty("GROUP") as String?) ?: "com.github.basheerpaliyathu"
            artifactId = "pixeldiet"
            version = (findProperty("VERSION_NAME") as String?) ?: "0.1.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("PixelDiet")
                description.set("Pure-JVM Android image compression — no native libraries, WeChat-style strategy, WebP output.")
                url.set("https://github.com/basheerpaliyathu/PixelDiet")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

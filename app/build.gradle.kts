plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wear.novelreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wear.novelreader"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }

    lint {
        abortOnError = false
    }
}

tasks.withType<Test>().configureEach {
    doFirst {
        val asciiRoot = File(System.getProperty("java.io.tmpdir"), "wear-novel-test-classpath")
        runCatching { asciiRoot.deleteRecursively() }
        asciiRoot.mkdirs()
        val remapped = classpath.map { original ->
            if (!original.exists() || original.path.all { it.code < 128 }) {
                original
            } else {
                val target = File(
                    asciiRoot,
                    original.name + "-" + Math.abs(original.absolutePath.hashCode())
                )
                if (original.isDirectory) {
                    original.copyRecursively(target, overwrite = true)
                } else {
                    target.parentFile?.mkdirs()
                    original.copyTo(target, overwrite = true)
                }
                target
            }
        }
        classpath = files(remapped)
    }
}

dependencies {
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment:1.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}


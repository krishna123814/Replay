plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.simplebrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.simplebrowser"
        minSdk = 24
        targetSdk = 34

        val vcProp = (project.findProperty("vc") as String?)?.toIntOrNull()
        versionCode = vcProp ?: (System.currentTimeMillis() / 60000L).toInt()
        versionName = "4.1"

        // GitHub Actions -PgithubRepo=<owner/repo> deta hai; na mile (local build) to ye default repo
        val repoProp = (project.findProperty("githubRepo") as String?)
            ?.takeIf { it.isNotBlank() } ?: "krishna123814/Replay"
        buildConfigField("String", "UPDATE_REPO", "\"$repoProp\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Keystore GitHub Secrets se build ke waqt app/lion-release.jks me banti hai (repo me nahi).
    // Secrets na hon to signing config bante hi nahi, build debug key se chalti hai.
    val lionKs = file("lion-release.jks")
    val lionPass = System.getenv("LION_STORE_PASSWORD")
    val lionAlias = System.getenv("LION_KEY_ALIAS") ?: "lion"
    val lionKeyPass = System.getenv("LION_KEY_PASSWORD") ?: lionPass
    if (lionKs.exists() && !lionPass.isNullOrEmpty()) {
        signingConfigs {
            create("lion") {
                storeFile = lionKs
                storePassword = lionPass
                keyAlias = lionAlias
                keyPassword = lionKeyPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("lion")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("lion")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // File Manager (MyFiles) ke liye
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.guava:guava:33.2.1-android")
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
}

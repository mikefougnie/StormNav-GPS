plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stormnav.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stormnav.app"
        minSdk = 26
        targetSdk = 36
        // CI builds count up (101, 102, …) so every new APK installs as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100) ?: 100
        versionName = "1.6.0"
        // Optional shared-report server, e.g.  gradle :app:assembleDebug -PstormnavServer=https://host.example
        val reportServer = (project.findProperty("stormnavServer") as String?) ?: ""
        buildConfigField("String", "STORMNAV_SERVER", "\"$reportServer\"")
        // "Buy me a coffee" links; leave blank to hide that option.
        buildConfigField("String", "SUPPORT_PAYPAL", "\"${project.findProperty("supportPaypal") ?: ""}\"")
        buildConfigField("String", "SUPPORT_VENMO", "\"${project.findProperty("supportVenmo") ?: ""}\"")
        // Optional free openrouteservice key (backup routing). Blank = FOSSGIS and the OSRM demo only.
        buildConfigField("String", "ORS_API_KEY", "\"${project.findProperty("orsApiKey") ?: ""}\"")
        buildConfigField("String", "TOMTOM_API_KEY", "\"${project.findProperty("tomtomApiKey") ?: ""}\"")
    }

    // Release key: app/signing/stormnav-release.jks (password-protected PKCS12). The password is
    // the GitHub Actions secret STORMNAV_KEYSTORE_PASSWORD; without it builds fall back to the
    // debug key so nothing breaks. Keep the password safe: Play Store updates need this key.
    val keystorePassword = System.getenv("STORMNAV_KEYSTORE_PASSWORD").orEmpty()
    signingConfigs {
        create("stormnav") {
            storeFile = file("signing/stormnav-release.jks")
            storePassword = keystorePassword
            keyAlias = "stormnav"
            keyPassword = keystorePassword
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePassword.isNotEmpty()) signingConfigs.getByName("stormnav")
                else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.5.4")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android's copy is a stub off-device).
    testImplementation("org.json:json:20240303")
}

// Show test output (the live search test prints what the real services returned).
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

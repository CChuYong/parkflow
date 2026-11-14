import java.io.File
import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Credentials come only from an explicitly supplied file, never Gradle arguments.
val releaseSigningProperties = System.getenv("PARKFLOW_SIGNING_PROPERTIES")?.let { path ->
    val properties = Properties()
    try {
        val propertiesFile = file(path)
        if (!propertiesFile.isFile || !propertiesFile.canRead()) {
            throw IllegalArgumentException()
        }
        propertiesFile.inputStream().use { properties.load(it) }
    } catch (_: Exception) {
        // Parser/I/O exception details may contain credential text.
        throw GradleException("Unable to load PARKFLOW_SIGNING_PROPERTIES; provide a readable properties file")
    }
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword", "storeType").forEach { key ->
        if (properties.getProperty(key).isNullOrBlank()) {
            throw GradleException("Missing release signing property: $key")
        }
    }
    val keyStoreFile = File(properties.getProperty("storeFile"))
    if (!keyStoreFile.isAbsolute || !keyStoreFile.isFile || !keyStoreFile.canRead()) {
        throw GradleException("Invalid release signing property: storeFile (requires a readable absolute path)")
    }
    if (properties.getProperty("storeType") != "PKCS12") {
        throw GradleException("Invalid release signing property: storeType (requires PKCS12)")
    }
    properties
}
android {
    namespace = "io.parkflow.probe"
    compileSdk = 34
    useLibrary("android.car")
    defaultConfig { applicationId = "io.parkflow.probe"; minSdk = 28; targetSdk = 34; versionCode = 10; versionName = "0.10" }
    buildFeatures { buildConfig = true }
    signingConfigs {
        releaseSigningProperties?.let { properties ->
            create("localRelease") {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                storeType = properties.getProperty("storeType")
            }
        }
    }
    buildTypes {
        getByName("debug") { buildConfigField("boolean", "PARKING_NAV_POC", "true") }
        getByName("release") {
            buildConfigField("boolean", "PARKING_NAV_POC", "false")
            if (releaseSigningProperties != null) signingConfig = signingConfigs.getByName("localRelease")
        }
        // Explicit emulator demonstration variant; signing is not a vehicle-policy exemption.
        create("demo") {
            initWith(getByName("release"))
            isDebuggable = false
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "PARKING_NAV_POC", "true")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("ai.pleos.playground:NaviHelper:3.0.5"); testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303") }

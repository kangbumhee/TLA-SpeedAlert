import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun mapboxToken(): String {
    val props = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        props.load(localFile.inputStream())
    }
    val fromFile = props.getProperty("MAPBOX_ACCESS_TOKEN", "").trim()
    val fromEnv = (System.getenv("MAPBOX_ACCESS_TOKEN") ?: "").trim()
    val fromGradle = (project.findProperty("MAPBOX_ACCESS_TOKEN") as String?)?.trim().orEmpty()
    return fromFile.ifEmpty { fromEnv }.ifEmpty { fromGradle }
}

fun String.escapeForBuildConfigString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

val useMapbox: Boolean =
    (project.findProperty("USE_MAPBOX")?.toString()?.equals("true", ignoreCase = true) == true)

fun osrmRouteBaseUrl(): String {
    val p = (project.findProperty("OSRM_ROUTE_BASE_URL") as String?)?.trim().orEmpty()
    val fromEnv = (System.getenv("OSRM_ROUTE_BASE_URL") ?: "").trim()
    return p.ifEmpty { fromEnv }
}

android {
    namespace = "com.teslcan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.teslcan.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "MAPBOX_ACCESS_TOKEN",
            "\"${mapboxToken().escapeForBuildConfigString()}\""
        )
        buildConfigField("boolean", "USE_MAPBOX", useMapbox.toString())
        buildConfigField(
            "String",
            "OSRM_ROUTE_BASE_URL",
            "\"${osrmRouteBaseUrl().escapeForBuildConfigString()}\""
        )
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}

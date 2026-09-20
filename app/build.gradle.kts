plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.edilmilan.ge360"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.edilmilan.ge360"
        minSdk = 26
        targetSdk = 35
        versionCode = 2126
        versionName = "0.21.25"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:bridge"))
    implementation(project(":feature:home"))
    implementation(project(":feature:clienti"))
    implementation(project(":feature:cantieri"))
    implementation(project(":feature:centrocantiere"))
    implementation(project(":feature:giornata"))
    implementation(project(":feature:messaggi"))
    implementation(project(":feature:spese"))
    implementation(project(":feature:scanner"))
    implementation(project(":feature:preventivi"))
    implementation(project(":feature:planimetrie"))
    implementation(project(":feature:attrezzi"))
    implementation(project(":feature:galleria"))
    implementation(project(":feature:fisco"))
    implementation(project(":feature:backup"))
    implementation(project(":feature:marketing"))
    implementation(project(":feature:jarvis"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}

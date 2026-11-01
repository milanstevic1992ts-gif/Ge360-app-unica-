plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.edilmilan.ge360.backup"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:backup"))
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
    implementation(project(":feature:agenda"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}

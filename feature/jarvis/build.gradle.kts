plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.edilmilan.ge360.jarvis"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:bridge"))
    implementation(project(":feature:clienti"))
    implementation(project(":feature:cantieri"))
    implementation(project(":feature:spese"))
    implementation(project(":feature:preventivi"))
    implementation(project(":feature:attrezzi"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}

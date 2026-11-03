plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.edilmilan.ge360.core.common"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
    // api: il design system espone RecyclerView e AppCompat nelle sue firme
    // (GeListContainer, BaseGeActivity), quindi i moduli feature li ereditano
    // senza doverli ridichiarare uno per uno.
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.recyclerview:recyclerview:1.3.2")
    api("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}

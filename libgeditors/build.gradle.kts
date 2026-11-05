// Editor UI framework: base activity, tile viewer, PDF viewer
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "lib.editors.gbase"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    buildTypes {
        // JaCoCo report of the unit tests (createDebugUnitTestCoverageReport) for the CI's
        // coverage check
        getByName("debug") {
            enableUnitTestCoverage = true
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
    implementation(project(":libtoolkit"))
    // MessengerClient: users to mention in comments (DocSpace)
    implementation(project(":libshared"))
    implementation(project(":libeditors"))
    implementation(project(":libresources"))

    implementation(libs.ktx)
    implementation(libs.appcompat)
    implementation(libs.google.material)
    implementation(libs.lifecycle.runtime)
    implementation(libs.kotlin.coroutines.android)

    testImplementation(libs.junit)
}

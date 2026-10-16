import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "no.varde.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "no.varde.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.ibm.icu:icu4j:76.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.0")
}

tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") exclude("**/GoHistoryTest*")
}

tasks.register<Test>("goHistoryTest") {
    description = "Verify Android snapshot browsing against an actual temporary Go/SQLite backend."
    group = "verification"
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    dependsOn(unitTests)
    testClassesDirs = unitTests.get().testClassesDirs
    classpath = unitTests.get().classpath
    include("**/GoHistoryTest.class")
    systemProperty("varde.backend.dir", rootProject.file("../backend").canonicalPath)
    inputs.files(fileTree(rootProject.file("../backend")) {
        include("**/*.go", "**/*.sql", "go.mod", "go.sum")
    })
}

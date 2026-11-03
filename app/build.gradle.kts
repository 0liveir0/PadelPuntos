plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.padelpuntos.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.padelpuntos.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// Descarga automática de los modelos de MediaPipe (gestos de mano y postura corporal)
val models = mapOf(
    "gesture_recognizer.task" to "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task",
    "pose_landmarker_lite.task" to "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
)
val assetsDir = file("src/main/assets")
val downloadModels by tasks.registering {
    outputs.dir(assetsDir)
    doLast {
        assetsDir.mkdirs()
        models.forEach { (name, url) ->
            val f = File(assetsDir, name)
            if (!f.exists()) uri(url).toURL().openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }

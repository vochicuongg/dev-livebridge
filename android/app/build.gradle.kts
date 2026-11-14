import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

fun releaseSigningProperty(name: String): String {
    return keystoreProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("Missing '$name' in android/key.properties")
}

android {
    namespace = "com.kakao.taxi"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.locnall.KimGiSa"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // Samsung One UI 7 exposes the vendor Now Bar on Android 15 (API 35).
        minSdk = 35
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            // Both target devices (Galaxy Z Flip5 and Galaxy A36) are
            // arm64. Shipping only arm64-v8a native libraries cuts the
            // universal APK size substantially without changing any
            // feature; remove this filter to build a universal APK again.
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // Round 149 (APK size): ship arm64-v8a native libraries
            // only. .so files are stored uncompressed in the APK, so
            // a second ABI costs ~22 MB (libflutter.so + libapp.so
            // again). Both target devices are arm64 and minSdk 35
            // implies a 64-bit device, so the other ABIs can never
            // run this app. This is a backstop behind the abiFilters
            // above: whatever the Flutter plugin builds, only arm64
            // is packaged.
            excludes += setOf(
                "lib/armeabi-v7a/**",
                "lib/armeabi/**",
                "lib/x86/**",
                "lib/x86_64/**"
            )
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
                storeFile = rootProject.file(releaseSigningProperty("storeFile"))
                storePassword = releaseSigningProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Round 149 (APK size): R8 minify + shrink resources.
            // Safe here: the app's reflection targets are Android
            // framework classes only (Notification.semFlags,
            // RemoteViews.mActions, SystemProperties, ...), never app
            // classes, and every getIdentifier call targets another
            // package (SystemUI / Samsung), so shrinking the app's
            // own code and resources cannot drop anything looked up
            // by name. Entry points are kept by proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

gradle.taskGraph.whenReady {
    if (!hasReleaseSigning && allTasks.any { it.name.contains("Release") }) {
        throw GradleException(
            "Release signing is not configured. Create android/key.properties from " +
                "android/key.properties.example and keep the keystore out of git."
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("io.github.d4viddf:hyperisland_kit:0.4.3")
}

flutter {
    source = "../.."
}

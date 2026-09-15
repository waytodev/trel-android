plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "to.trel.okhttp"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
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
    api(project(":trel"))
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
}

mavenPublishing {
    coordinates("to.trel", "trel-okhttp", project.version.toString())
    pom {
        name.set("Trel OkHttp")
        description.set("OkHttp interceptor that reports HTTP client spans and breadcrumbs to Trel.")
    }
}

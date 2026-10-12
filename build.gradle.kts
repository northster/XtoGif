// AGP 9 with its built-in Kotlin support.
buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.1.1")
        classpath(kotlin("gradle-plugin", version = "2.4.0"))
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

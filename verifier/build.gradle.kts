plugins {
    kotlin("jvm")
    application
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("dev.crashhunter.verifier.MainKt")
    applicationName = "crashhunter-verify"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

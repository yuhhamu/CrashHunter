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
    implementation("com.formdev:flatlaf:3.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.crashhunter.app.MainKt")
    applicationName = "CrashHunter"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true")
}

tasks.jar {
    archiveBaseName.set("crashhunter")
    manifest { attributes("Implementation-Title" to "CrashHunter", "Implementation-Version" to project.version) }
}

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(tasks.installDist)
    val lib = layout.buildDirectory.dir("install/CrashHunter/lib")
    val out = layout.buildDirectory.dir("jpackage")
    val icon = rootProject.layout.projectDirectory.file("installer/crashhunter.ico")
    doFirst { delete(out) }
    val javaHome = System.getProperty("java.home")
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) "$javaHome/bin/jpackage.exe" else "$javaHome/bin/jpackage"
    commandLine(
        exe, "--type", "app-image",
        "--name", "CrashHunter",
        "--app-version", project.version.toString(),
        "--vendor", "yuhhamu",
        "--input", lib.get().asFile.absolutePath,
        "--main-jar", "crashhunter-${project.version}.jar",
        "--main-class", "dev.crashhunter.app.MainKt",
        "--icon", icon.asFile.absolutePath,
        "--dest", out.get().asFile.absolutePath,
        "--add-modules", "java.base,java.desktop,java.logging,java.management,jdk.management,java.net.http,java.naming,java.prefs,java.xml,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.charsets,jdk.localedata",
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages --compress=zip-6",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Xmx512m",
    )
}

val cfKeyDir = layout.buildDirectory.dir("generated/cfkey")
val writeCfKey by tasks.registering {
    val key = providers.environmentVariable("CH_CF_APP_KEY")
    val dbToken = providers.environmentVariable("CH_DB_TOKEN")
    inputs.property("hasKey", key.map { it.isNotBlank() }.orElse(false))
    inputs.property("dbToken", dbToken.orElse(""))
    outputs.dir(cfKeyDir)
    doLast {
        val f = cfKeyDir.get().file("crashhunter/cf-app.key").asFile
        val k = key.orNull?.trim()
        if (k.isNullOrEmpty()) f.delete() else { f.parentFile.mkdirs(); f.writeText(k) }
        val t = cfKeyDir.get().file("crashhunter/db-app.token").asFile
        val v = dbToken.orNull?.trim()
        if (v.isNullOrEmpty()) t.delete() else { t.parentFile.mkdirs(); t.writeText(v) }
    }
}
sourceSets.main { resources.srcDir(writeCfKey) }

tasks.processResources {
    from(rootProject.layout.projectDirectory) {
        include("LICENSE", "NOTICE.md")
        into("crashhunter")
    }
}

dependencies { testImplementation("org.junit.jupiter:junit-jupiter:5.11.4"); testRuntimeOnly("org.junit.platform:junit-platform-launcher") }
tasks.test { useJUnitPlatform() }

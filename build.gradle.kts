import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins { kotlin("jvm") version "2.2.20" apply false }

val fourkApi = providers.gradleProperty("fourkApi").get()
val kotlinVersion = providers.gradleProperty("kotlinVersion").get()

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenLocal(); mavenCentral(); maven("https://jitpack.io") }
    dependencies {
        // both are on the app's classpath at runtime; a plugin jar holds only its own classes
        "compileOnly"(fourkApi)
        "compileOnly"("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    }
    // Java 17 bytecode from whichever JDK runs Gradle (d8 and the desktop app both read it)
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    extensions.configure<JavaPluginExtension> { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

// what d8 resolves against (never packaged)
repositories { mavenLocal(); mavenCentral(); maven("https://jitpack.io") }
val d8Lib: Configuration by configurations.creating
dependencies {
    d8Lib(fourkApi) { isTransitive = false }
    d8Lib("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion") { isTransitive = false }
}

fun sdkDir(): File {
    val local = rootProject.file("local.properties").takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("sdk.dir") }
    val path = local ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Set ANDROID_HOME (or sdk.dir in local.properties): d8 comes from the Android build-tools")
    return File(path)
}
fun newest(dir: File) = dir.listFiles()!!.filter { it.isDirectory }
    .maxByOrNull { it.name.split('.', '-').mapNotNull(String::toIntOrNull).fold(0L) { a, n -> a * 1000 + n } } ?: error("Nothing in $dir")
fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * make: every plugin → classes.jar (desktop) → d8 → classes.dex (Android) → <Name>.4ks (a zip with manifest.json),
 * then build/repo/plugins.json listing them all with their version, url, sha256 and minApi.
 */
tasks.register("make") {
    group = "4k stream"
    dependsOn(subprojects.map { "${it.path}:jar" })
    doLast {
        val out = layout.buildDirectory.dir("repo").get().asFile.apply { deleteRecursively(); mkdirs() }
        val sdk = sdkDir()
        val d8 = File(newest(File(sdk, "build-tools")), "lib/d8.jar")
        val androidJar = File(newest(File(sdk, "platforms")), "android.jar")
        val libs = d8Lib.resolve()
        val index = subprojects.map { p ->
            @Suppress("UNCHECKED_CAST")
            val meta = JsonSlurper().parse(p.file("plugin.json")) as Map<String, Any?>
            val name = meta["internalName"] as String
            val jar = p.tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val work = p.layout.buildDirectory.dir("dex").get().asFile.apply { deleteRecursively(); mkdirs() }
            val cmd = listOf("java", "-cp", d8.path, "com.android.tools.r8.D8", "--release", "--min-api", "26", "--lib", androidJar.path) +
                libs.flatMap { listOf("--classpath", it.path) } + listOf("--output", work.path, jar.path)
            val proc = ProcessBuilder(cmd).inheritIO().start()
            check(proc.waitFor() == 0) { "d8 failed for $name" }
            val pkg = File(out, "$name.4ks")
            ZipOutputStream(pkg.outputStream()).use { z ->
                fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
                put("manifest.json", JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
                    "internalName" to name, "name" to meta["name"], "version" to meta["version"].toString(),
                    "api" to (meta["minApi"] ?: 1), "entry" to meta["entry"]))).toByteArray())
                put("classes.jar", jar.readBytes())
                put("classes.dex", File(work, "classes.dex").readBytes())
            }
            meta.filterKeys { it != "entry" } + mapOf("version" to meta["version"].toString(), "url" to pkg.name, "sha256" to sha256(pkg), "minApi" to (meta["minApi"] ?: 1))
        }
        File(out, "plugins.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "api" to 1, "name" to providers.gradleProperty("repoName").get(), "author" to providers.gradleProperty("repoAuthor").get(), "plugins" to index))))
        println("made ${index.size} plugin(s) in $out")
    }
}

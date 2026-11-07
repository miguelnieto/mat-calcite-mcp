import java.net.URI
import javax.inject.Inject
import java.security.MessageDigest

// org.eclipse.mat.api 1.17.0 for Java 17. MAT 1.17.0 compiled this one bundle for Java 21; every other MAT and
// Eclipse bundle the server uses already runs on Java 17. Its only Java 21 trace is a bridge method javac adds for
// List.reversed() (new in Java 21) to a List implementation. So the released source is compiled with --release 17,
// and the bundle is the released jar with these class files and a manifest that asks for Java 17.
plugins {
    java
}

// The commit MAT 1.17.0 was built from: the last one before its build qualifier (2026-06-01 19:33 UTC). Its classes
// have the same fields and methods as the released ones, apart from compiler conventions (enum value arrays,
// synthetic members) and the Java 21 bridge method.
val matCommit = "8a2a95b9ffa13ff2f7d137100bc11484ac5b8203"
// SHA-256 over "<path>\n<sha256 of file>\n" for each file of plugins/org.eclipse.mat.api/src, sorted by path. GitHub
// does not promise stable archive bytes, so the extracted sources are checked, not the archive.
val sourcesDigest = "8f577278e02aeeeab0d8742474c20a9bf4fa641b62d8315922a257458a489e0e"
val releasedVersion = "1.17.0.202606011933"
val rebuiltVersion = "$releasedVersion-java17"

val released: Configuration by configurations.creating { isTransitive = false }

dependencies {
    released("mat-1.17.0:org.eclipse.mat.api:$releasedVersion")
    // What the api bundle compiles against: MAT's report bundle and the Eclipse runtime
    compileOnly("mat-1.17.0:org.eclipse.mat.report:$releasedVersion")
    // MAT annotates one class with @SuppressFBWarnings; the annotation is not needed at run time
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.5")
}
apply(from = rootProject.file("gradle/eclipse-platform.gradle.kts"))
configurations.named("compileOnly") { extendsFrom(configurations["eclipsePlatform"]) }

val sourcesDir = layout.buildDirectory.dir("mat-api-src")

// File and archive operations for use while tasks run; Task.project is deprecated there
interface FileServices {
    @get:Inject val fs: FileSystemOperations
    @get:Inject val archives: ArchiveOperations
}
val fileServices = objects.newInstance<FileServices>()

val fetchSources by tasks.registering {
    description = "Downloads MAT's sources at $matCommit and keeps plugins/org.eclipse.mat.api/src"
    inputs.property("commit", matCommit)
    inputs.property("digest", sourcesDigest)
    outputs.dir(sourcesDir)
    doLast {
        val archive = temporaryDir.resolve("mat-$matCommit.tar.gz")
        if (!archive.exists()) {
            URI("https://github.com/eclipse-mat/mat/archive/$matCommit.tar.gz").toURL().openStream().use { input ->
                archive.outputStream().use { input.copyTo(it) }
            }
        }
        val target = sourcesDir.get().asFile
        target.deleteRecursively()
        val prefix = "mat-$matCommit/plugins/org.eclipse.mat.api/src/"
        fileServices.fs.copy {
            from(fileServices.archives.tarTree(fileServices.archives.gzip(archive))) {
                include("$prefix**")
                eachFile { path = path.removePrefix(prefix) }
                includeEmptyDirs = false
            }
            into(target)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        target.walkTopDown().filter { it.isFile }.map { it.relativeTo(target).invariantSeparatorsPath }.sorted()
            .forEach { path ->
                val fileHash = MessageDigest.getInstance("SHA-256").digest(target.resolve(path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                digest.update("$path\n$fileHash\n".toByteArray())
            }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sourcesDigest) {
            target.deleteRecursively()
            throw GradleException("MAT api sources at $matCommit have digest $actual, expected $sourcesDigest")
        }
    }
}

sourceSets.main {
    java.setSrcDirs(listOf(files(sourcesDir).builtBy(fetchSources)))
    resources.setSrcDirs(emptyList<Any>())
}

tasks.compileJava {
    options.release.set(17)
    options.encoding = "UTF-8"
    // package-info classes as MAT's own build (Eclipse's compiler) produces them; no annotation processing
    options.compilerArgs.addAll(listOf("-Xpkginfo:always", "-proc:none", "-nowarn"))
}

tasks.jar {
    archiveFileName.set("org.eclipse.mat.api-$rebuiltVersion.jar")
    // Everything but the classes comes from the released bundle: plugin.xml, properties, schemas, about files.
    // Its signature no longer matches once classes change, so it is left out, and so are the per-file digests.
    from(provider { zipTree(released.singleFile) }) {
        exclude("**/*.class", "META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        // Values of a merged manifest win over attributes(), so the two changes are made while merging
        from(provider { zipTree(released.singleFile).matching { include("META-INF/MANIFEST.MF") }.singleFile }) {
            eachEntry {
                when {
                    section != null -> exclude()
                    key == "Bundle-Version" -> value = rebuiltVersion
                    key == "Require-Capability" -> value = "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version=17))\""
                }
            }
        }
    }
}

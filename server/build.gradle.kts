import aQute.bnd.gradle.Bundle
import aQute.bnd.gradle.Export
import aQute.bnd.gradle.Resolve
import aQute.bnd.gradle.TestOSGi

plugins {
    java
    id("biz.aQute.bnd.builder") version "7.4.0"
}

// Built on JDK 21 for now; the bytecode and the JDK API used are Java 17's
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

apply(from = rootProject.file("gradle/mat-platform.gradle.kts"))

// Calcite and the libraries it needs at run time are embedded in the server bundle (Bundle-ClassPath), not
// installed as bundles of their own: most of them are not OSGi bundles, and one class loader for Calcite, Janino
// and the generated query code is what Calcite expects. The set is what the mat-calcite-plugin update site ships,
// at the versions of its security update, plus httpcore5 and slf4j-api, which a MAT install used to provide.
val embedded: Configuration by configurations.creating {
    isTransitive = false
}
configurations.compileClasspath { extendsFrom(embedded) }
configurations.testCompileClasspath { extendsFrom(embedded) }

dependencies {
    // Compiling sees all of Calcite's API; only the embedded jars ship
    compileOnly("org.apache.calcite:calcite-core:1.42.0")
    testCompileOnly("org.apache.calcite:calcite-core:1.42.0")

    // JUnit 4 as OSGi bundles, which bnd's tester (biz.aQute.tester) runs the tests with
    testImplementation("eclipse-4.40:org.junit:4.13.2.v20240929-1000")
    testImplementation("eclipse-4.40:org.hamcrest:3.0.0")

    listOf(
        "org.apache.calcite:calcite-core:1.42.0",
        "org.apache.calcite:calcite-linq4j:1.42.0",
        "org.apache.calcite.avatica:avatica-core:1.29.0",
        "com.fasterxml.jackson.core:jackson-core:2.22.4",
        "com.google.guava:guava:33.8.0-jre",
        "com.google.guava:failureaccess:1.0.3",
        "com.google.protobuf:protobuf-java:3.25.8",
        "com.jayway.jsonpath:json-path:2.10.0",
        "net.minidev:json-smart:2.6.1",
        "net.minidev:accessors-smart:2.6.1",
        "commons-codec:commons-codec:1.16.0",
        "org.apache.commons:commons-lang3:3.21.0",
        "org.apache.commons:commons-math3:3.6.1",
        "org.apache.commons:commons-text:1.11.0",
        "org.codehaus.janino:janino:3.1.12",
        "org.codehaus.janino:commons-compiler:3.1.12",
        "org.jooq:joou-java-6:0.9.5",
        "org.locationtech.jts:jts-core:1.19.0",
        "org.apache.httpcomponents.client5:httpclient5:5.6.5",
        "org.apache.httpcomponents.core5:httpcore5:5.4.4",
        "org.slf4j:slf4j-api:2.0.17",
    ).forEach { add("embedded", it) }
}

tasks.jar {
    from(embedded) { into("lib") }
    bundle {
        // bnd.bnd builds Bundle-ClassPath from this list
        properties.put("embedded.jars", provider { embedded.joinToString(",") { "lib/${it.name}" } })
    }
}

val resolve by tasks.registering(Resolve::class) {
    description = "Resolves mat-calcite-mcp.bndrun against the bundles above and records the result in its -runbundles"
    bndrun.set(file("mat-calcite-mcp.bndrun"))
}

val export by tasks.registering(Export::class) {
    description = "Writes the MCP server as one runnable jar: Equinox plus every bundle in -runbundles"
    dependsOn(resolve)
    bndrun.set(file("mat-calcite-mcp.bndrun"))
    exporter.set("bnd.executablejar")
}

// The tests run inside Equinox, since MAT needs its extension registry. They go into a bundle of their own with the
// server's classes and the embedded jars, but without the activator that would start the stdio server.
val testBundle by tasks.registering(Bundle::class) {
    description = "Builds the test bundle: server classes, test classes and the embedded jars"
    archiveClassifier.set("tests")
    from(sourceSets.main.get().output)
    from(sourceSets.test.get().output)
    from(embedded) { into("lib") }
    bundle {
        bndfile.set(file("test.bnd"))
        classpath(sourceSets.test.get().compileClasspath)
        properties.put("embedded.jars", provider { embedded.joinToString(",") { "lib/${it.name}" } })
    }
}

val resolveTests by tasks.registering(Resolve::class) {
    description = "Resolves test.bndrun and records the result in its -runbundles"
    bndrun.set(file("test.bndrun"))
    bundles(sourceSets.test.get().runtimeClasspath, testBundle)
}

val testOSGi by tasks.registering(TestOSGi::class) {
    description = "Runs AllTests inside Equinox with MAT"
    group = "verification"
    dependsOn(resolveTests)
    bndrun.set(file("test.bndrun"))
    bundles(sourceSets.test.get().runtimeClasspath, testBundle)
}

// Gradle's own test task would run the tests on a plain class path, where MAT has no extension registry
tasks.test { enabled = false }
tasks.check { dependsOn(testOSGi) }

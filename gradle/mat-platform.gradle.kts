// The headless part of MAT 1.17.0 and the Eclipse runtime it ships with, for modules that run MAT. Its api bundle
// is the Java 17 rebuild from :mat-api-java17; parser, hprof and report are the released bundles, which already run
// on Java 17.
val matVersion = "1.17.0.202606011933"

apply(from = rootProject.file("gradle/eclipse-platform.gradle.kts"))
configurations.named("implementation") { extendsFrom(configurations["eclipsePlatform"]) }

dependencies {
    add("implementation", project(":mat-api-java17"))
    listOf("org.eclipse.mat.parser", "org.eclipse.mat.hprof", "org.eclipse.mat.report").forEach {
        add("implementation", "mat-1.17.0:$it:$matVersion")
    }
}

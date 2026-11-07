// Eclipse MAT and the Eclipse platform bundles are not on Maven Central. Their p2 update sites keep the bundles as
// plain jars in plugins/, named <bundle>_<version>.jar, so Gradle reads each site as an Ivy repository. The group of
// a dependency names the site: "eclipse-4.40:org.eclipse.core.runtime:3.34.200.v20251220-0953".
allprojects {
    repositories {
        mavenCentral()
        p2Site("eclipse-4.40", "https://download.eclipse.org/eclipse/updates/4.40/R-4.40-202606010713")
        p2Site("mat-1.17.0", "https://download.eclipse.org/mat/1.17.0/update-site")
    }
}

fun RepositoryHandler.p2Site(site: String, location: String) {
    ivy {
        name = site
        url = uri(location)
        patternLayout { artifact("plugins/[module]_[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup(site) }
    }
}

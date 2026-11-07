// The Eclipse 4.40 runtime (R-4.40-202606010713) that MAT 1.17.0 ships with, at the exact versions of its bundles:
// the closure of the Require-Bundle and Import-Package headers of MAT's api, parser, hprof and report bundles, plus
// what that closure needs at run time. All of them run on Java 17. Modules use them through the eclipsePlatform
// configuration; the bundles come from the p2 site declared in the root build script.
val platformBundles = mapOf(
    "org.eclipse.osgi" to "3.24.200.v20260515-1403",
    "org.eclipse.core.runtime" to "3.34.200.v20251220-0953",
    "org.eclipse.equinox.registry" to "3.12.600.v20250906-0651",
    "org.eclipse.equinox.common" to "3.20.400.v20260512-1534",
    "org.eclipse.equinox.app" to "1.7.600.v20251211-1038",
    "org.eclipse.equinox.preferences" to "3.12.100.v20251111-0704",
    "org.eclipse.core.jobs" to "3.15.800.v20260325-1353",
    "org.eclipse.core.contenttype" to "3.9.800.v20251105-1620",
    "org.eclipse.core.resources" to "3.24.0.v20260518-1150",
    "org.eclipse.core.filesystem" to "1.11.400.v20251107-0507",
    "org.eclipse.core.commands" to "3.12.500.v20251103-0733",
    "org.eclipse.core.expressions" to "3.9.600.v20260514-2026",
    "org.osgi.service.prefs" to "1.1.2.202109301733",
    "com.sun.jna" to "5.18.1.v20251001-0800",
    "com.sun.jna.platform" to "5.18.1",
    // Declarative Services: the content type manager MAT uses to recognise dump formats is a DS component
    "org.apache.felix.scr" to "2.2.18",
    "org.osgi.service.component" to "1.5.1.202212101352",
    "org.osgi.util.promise" to "1.3.0.202212101352",
    "org.osgi.util.function" to "1.2.0.202109301733",
)

configurations.create("eclipsePlatform") { isTransitive = false }
dependencies {
    platformBundles.forEach { (bundle, version) -> add("eclipsePlatform", "eclipse-4.40:$bundle:$version") }
}

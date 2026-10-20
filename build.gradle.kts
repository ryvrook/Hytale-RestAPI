plugins {
    java
}

group = "dev.ryvrook"
version = "0.3.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Which Hytale server artifact to compile against. Defaults live in gradle.properties
// and can be overridden with -Phytale_channel=release -Phytale_version=0.7.1
val hytaleChannel = (project.findProperty("hytale_channel") as String?)?.trim() ?: "release"
val hytaleVersion = (project.findProperty("hytale_version") as String?)?.trim() ?: "0.7.1"
require(hytaleChannel == "release" || hytaleChannel == "pre-release") {
    "hytale_channel must be \"release\" or \"pre-release\", got \"$hytaleChannel\""
}

// Sibling jars for the suite route groups, never shipped inside the mod. Each one is given with -PtrackerApiJar,
// -PcoinageJar, -PdominionJar, -PfeatsJar, -PchronicleJar, -PatlasJar, -PlullabyJar and -PsocialBridgeJar, else the sibling checkout
// next to this one, else the checkout under ~/Projects. Only the unit classes under dev.ryvrook.restapi.suite.<id>
// touch them, loaded by name after the presence check, so RestAPI still loads on a server without any of them.
fun siblingJar(property: String, vararg candidates: String): String {
    (project.findProperty(property) as String?)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val home = System.getProperty("user.home")
    val paths = candidates.flatMap { listOf("../$it", "$home/Projects/$it") }
    return paths.firstOrNull { file(it).exists() } ?: paths.first()
}
val trackerApiJar = siblingJar("trackerApiJar", "trackerapi/build/libs/TrackerAPI-1.1.1.jar")
// The Coinage api jar holds the api packages only.
val coinageJar = siblingJar("coinageJar", "coinage/build/libs/Coinage-api-1.3.0.jar")
val dominionJar = siblingJar("dominionJar", "dominion/build/libs/DominionFactions-0.7.1.jar")
val featsJar = siblingJar("featsJar", "feats/build/libs/Feats-1.7.4.jar")
val chronicleJar = siblingJar("chronicleJar", "chronicle-stats/build/libs/ChronicleStats-1.5.0.jar")
val atlasJar = siblingJar("atlasJar", "atlas/build/libs/Atlas-1.9.0.jar")
val lullabyJar = siblingJar("lullabyJar", "lullaby/build/libs/Lullaby-1.1.0.jar")
val socialBridgeJar = siblingJar("socialBridgeJar", "socialbridge/build/libs/SocialBridge-0.2.0.jar")
val siblingJars = listOf(trackerApiJar, coinageJar, dominionJar, featsJar, chronicleJar, atlasJar, lullabyJar, socialBridgeJar)

repositories {
    mavenCentral()
    maven {
        name = "hytale-$hytaleChannel"
        url = uri("https://maven.hytale.com/$hytaleChannel")
    }
}

dependencies {
    // Provided by the Hytale server at runtime, never bundled into the mod jar.
    compileOnly("com.hypixel.hytale:Server:$hytaleVersion")
    // Optional at runtime, listed under OptionalDependencies. Only the suite units touch them.
    compileOnly(files(siblingJars))

    // Tests run without a server. The server jar is on the test classpath for Gson and the logger only.
    testImplementation("com.hypixel.hytale:Server:$hytaleVersion")
    // The suite tests drive each unit over stand in sibling APIs and need the sibling records and interfaces.
    testImplementation(files(siblingJars))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
    }
    // The engine logger insists on owning java.util.logging before anything logs.
    systemProperty("java.util.logging.manager", "com.hypixel.hytale.logger.backend.HytaleLogManager")
    // RESTAPI_WRITE_DOCS=1 lets SettingsDocTest write docs/SETTINGS.md again.
    environment("RESTAPI_WRITE_DOCS", System.getenv("RESTAPI_WRITE_DOCS") ?: "")
    // RESTAPI_BROWSER_CHECK=1 runs DashboardBrowserCheck, headless Chrome against the dashboard, see docs/DEVELOPMENT.md.
    for (name in listOf("RESTAPI_BROWSER_CHECK", "RESTAPI_BROWSER_PORT", "RESTAPI_BROWSER_OUT", "DASHBOARD_TOOLS")) {
        System.getenv(name)?.let { environment(name, it) }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

// The version above is the only place it is set. The manifest gets it through processResources.
val versionText = project.version.toString()
tasks.processResources {
    inputs.property("version", versionText)
    filesMatching("manifest.json") {
        filter { line -> line.replace("@version@", versionText) }
    }
}

tasks.jar {
    archiveBaseName.set("RestAPI")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

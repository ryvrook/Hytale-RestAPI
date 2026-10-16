plugins {
    java
}

group = "dev.ryvrook"
version = "0.1.0"

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

    // Tests run without a server. The server jar is on the test classpath for Gson and the logger only.
    testImplementation("com.hypixel.hytale:Server:$hytaleVersion")
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

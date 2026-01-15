plugins {
    java
    id("com.gradleup.shadow").version("8.3.3")
}

group = "com.dfsek"
version = "1.2.1"

repositories {
    mavenCentral()
    maven {
        name = "Solo Studios"
        url = uri("https://maven.solo-studios.ca/releases")
    }
    maven {
        name = "CodeMC"
        url = uri("https://repo.codemc.org/repository/maven-public/")
    }
    maven {
        name = "Jitpack"
        url = uri("https://jitpack.io")
    }
    maven {
        name = "JogAmp"
        url = uri("https://jogamp.org/deployment/maven/")
    }
}

val terraGitHash = "a159debe3"

val terraAddon: Configuration by configurations.creating
val bootstrapTerraAddon: Configuration by configurations.creating

dependencies {
    testImplementation("org.junit.jupiter", "junit-jupiter", "5.13.0")
    testRuntimeOnly("org.junit.platform", "junit-platform-launcher")
    implementation("com.dfsek:seismic:2.5.7")
    implementation("com.dfsek.terra:api:7.0.0-BETA+$terraGitHash")
    implementation("com.dfsek.terra:base:7.0.0-BETA+$terraGitHash")

    // Bootstrap addon loaders
    bootstrapTerraAddon("com.dfsek.terra:manifest-addon-loader:1.0.0-BETA+$terraGitHash")

    // Terra addons for noise configuration
    terraAddon("com.dfsek.terra:config-noise-function:1.2.0-BETA+$terraGitHash")
    terraAddon("com.dfsek.terra:library-image:1.1.0-BETA+$terraGitHash")

    compileOnly("org.jetbrains:annotations:26.0.2")
    implementation("commons-io:commons-io:2.19.0")
    implementation("com.google.guava:guava:33.4.8-jre")

    implementation("com.dfsek.tectonic", "yaml", "4.2.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")


    implementation("com.fifesoft:rstaui:3.3.1")
    implementation("com.fifesoft:rsyntaxtextarea:3.5.1")
    implementation("com.fifesoft:autocomplete:3.3.1")

    implementation("com.formdev:flatlaf:3.6")

    implementation("org.jogamp.jogl:jogl-all:2.5.0")
    runtimeOnly("org.jogamp.jogl:jogl-all:2.5.0:natives-linux-amd64")
    runtimeOnly("org.jogamp.jogl:jogl-all:2.5.0:natives-macosx-universal")
    runtimeOnly("org.jogamp.jogl:jogl-all:2.5.0:natives-windows-amd64")

    implementation("org.jogamp.gluegen:gluegen-rt:2.5.0")
    runtimeOnly("org.jogamp.gluegen:gluegen-rt:2.5.0:natives-linux-amd64")
    runtimeOnly("org.jogamp.gluegen:gluegen-rt:2.5.0:natives-macosx-universal")
    runtimeOnly("org.jogamp.gluegen:gluegen-rt:2.5.0:natives-windows-amd64")
}


val jar by tasks.getting(Jar::class) {
    manifest {
        attributes["Main-Class"] = "com.dfsek.noise.NoiseTool"
    }
}

val prepareDistAddons by tasks.creating(Sync::class) {
    group = "distribution"
    description = "Copies Terra addons to build/libs for standalone JAR usage"

    val terraAddonJars = terraAddon.resolvedConfiguration.firstLevelModuleDependencies.flatMap { dependency ->
        dependency.moduleArtifacts.map { it.file }
    }
    val terraBootstrapJars = bootstrapTerraAddon.resolvedConfiguration.firstLevelModuleDependencies.flatMap { dependency ->
        dependency.moduleArtifacts.map { it.file }
    }

    from(terraAddonJars)

    from(terraBootstrapJars) {
        into("bootstrap")
    }

    into(layout.buildDirectory.dir("libs/addons"))
}

tasks.build {
    dependsOn(tasks.named("shadowJar"))
    finalizedBy(prepareDistAddons)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
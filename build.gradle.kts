plugins {
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
}

group = "be.proleague"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("tools.jackson:jackson-bom:3.2.2")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * The directory holding node and npm.
 *
 * A shell finds them through nvm, but a Gradle daemon started from the IDE inherits a bare
 * PATH with no nvm entry on it, and plain `commandLine("npm", ...)` dies there with
 * "Exec failed, error=2, No such file or directory". So look in the installed nvm versions as
 * well as on PATH, newest version first.
 */
val nodeBin: File by lazy {
    val nvm = file("${System.getProperty("user.home")}/.nvm/versions/node")
        .listFiles().orEmpty().sortedDescending().map { File(it, "bin") }
    (System.getenv("PATH").orEmpty().split(File.pathSeparator).map(::File) + nvm)
        .firstOrNull { File(it, "npm").canExecute() && File(it, "node").canExecute() }
        ?: throw GradleException("npm not found on PATH or under ~/.nvm; the frontend cannot be built")
}

/** Vite writes straight into the static resources, so the jar always ships the current UI. */
val buildFrontend by tasks.registering(Exec::class) {
    workingDir = file("frontend")
    // Absolute, because Java resolves the command against its own PATH rather than the one
    // set below -- which npm still needs, since its launcher shells out to node by name.
    commandLine(File(nodeBin, "npm").absolutePath, "run", "build")
    environment("PATH", "$nodeBin${File.pathSeparator}${System.getenv("PATH").orEmpty()}")
    inputs.dir("frontend/src")
    inputs.files("frontend/index.html", "frontend/package.json", "frontend/vite.config.js")
    outputs.dir("src/main/resources/static")
}

tasks.processResources { dependsOn(buildFrontend) }

plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
}
allprojects {
    group = "meetup.sherlock"
    version = "0.1.0"
    repositories { mavenCentral() }
}
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

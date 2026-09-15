plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application { mainClass.set("meetup.sherlock.koog.KoogSherlockKt") }
dependencies {
    implementation(project(":incident-lab"))
    implementation(project(":live-lab"))
    implementation("ai.koog:koog-agents:1.2.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
tasks.named<JavaExec>("run") { standardInput = System.`in` }

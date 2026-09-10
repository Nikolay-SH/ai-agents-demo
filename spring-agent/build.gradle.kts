plugins { application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("meetup.sherlock.spring.SpringSherlock") }
dependencies {
    implementation(project(":incident-lab"))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-ollama")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
tasks.named<JavaExec>("run") { standardInput = System.`in` }

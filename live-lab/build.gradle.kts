plugins { `java-library`; application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("meetup.sherlock.live.LiveOperator") }
dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    runtimeOnly("org.postgresql:postgresql:42.7.8")
    testImplementation(platform("org.junit:junit-bom:5.14.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.named<JavaExec>("run") { standardInput = System.`in` }

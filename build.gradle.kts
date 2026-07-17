import io.gitlab.arturbosch.detekt.Detekt

plugins {
  kotlin("jvm") version "2.4.0"
  kotlin("plugin.serialization") version "2.4.0"
  id("com.ncorti.ktfmt.gradle") version "0.22.0"
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "org.antipathy"

version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
  implementation("io.ktor:ktor-server-core:3.5.1")
  implementation("io.ktor:ktor-server-metrics-micrometer:3.5.1")
  implementation("io.ktor:ktor-server-netty:3.5.1")
  implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")
  implementation("net.mamoe.yamlkt:yamlkt:0.13.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

  /* Test */
  testImplementation(kotlin("test"))
  testImplementation("com.redis:testcontainers-redis:2.2.4")
  testImplementation("io.mockk:mockk:1.14.11")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
  testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
  testImplementation("org.testcontainers:testcontainers:2.0.5")
}

detekt {
  buildUponDefaultConfig = true // preconfigure defaults
  allRules = false // don't want unstable rules.
  config.setFrom("$projectDir/detekt.yml")
  baseline =
      file("$projectDir/config/baseline.xml") // a way of suppressing issues before introducing
  // detekt
}

tasks.withType<Detekt>().configureEach {
  reports {
    html.required.set(true) // findings in your browser
    xml.required.set(true) // checkstyle format for integrations
    sarif.required.set(true) // standardized SARIF format for integrations with GitHub Code Scanning
    md.required.set(true) // simple Markdown format
  }
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }

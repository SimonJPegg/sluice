import io.gitlab.arturbosch.detekt.Detekt

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

group = "org.antipathy"

version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.config.yaml)
  implementation(libs.ktor.server.call.id)
  implementation(libs.ktor.server.call.logging)
  implementation(libs.ktor.server.metrics.micrometer)
  implementation(libs.lettuce.core)
  implementation(libs.yamlkt)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.micrometer.registry.prometheus)

  /* Test */
  testImplementation(kotlin("test"))
  testImplementation(libs.testcontainers.redis)
  testImplementation(libs.mockk)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers)
  testImplementation(libs.ktor.server.test.host)
}

detekt {
  buildUponDefaultConfig = true
  allRules = false
  config.setFrom("$projectDir/detekt.yml")
  baseline = file("$projectDir/config/baseline.xml")
}

tasks.withType<Detekt>().configureEach {
  reports {
    html.required.set(true)
    xml.required.set(true)
    sarif.required.set(true)
    md.required.set(true)
  }
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }

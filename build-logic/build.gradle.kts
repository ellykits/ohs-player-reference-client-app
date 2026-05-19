plugins {
  `kotlin-dsl`
}

repositories {
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
}

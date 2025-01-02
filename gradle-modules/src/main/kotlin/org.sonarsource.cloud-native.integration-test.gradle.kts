/*
 * SonarSource Cloud Native Gradle Modules
 * Copyright (C) 2024-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED
import org.sonarsource.cloudnative.gradle.IntegrationTestExtension

// Inspiration: https://docs.gradle.org/current/samples/sample_jvm_multi_project_with_additional_test_types.html

plugins {
    java
    id("org.sonarsource.cloud-native.java-conventions")
}

val integrationTest by sourceSets.creating
val integrationTestConfiguration = extensions.create<IntegrationTestExtension>("integrationTest")

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    inputs.dir(integrationTestConfiguration.testSources)
    inputs.property("SQ version", System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE"))
    inputs.property("keep SQ running", System.getProperty("keepSonarqubeRunning", "false"))
    useJUnitPlatform()

    testClassesDirs = integrationTest.output.classesDirs
    classpath = configurations[integrationTest.runtimeClasspathConfigurationName] + integrationTest.output

    if (System.getProperty("sonar.runtimeVersion") != null) {
        systemProperty("sonar.runtimeVersion", System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE"))
    }

    if (System.getProperty("keepSonarqubeRunning") != null) {
        systemProperty("keepSonarqubeRunning", System.getProperty("keepSonarqubeRunning"))
    }

    testLogging {
        // log the full stack trace (default is the 1st line of the stack trace)
        exceptionFormat = TestExceptionFormat.FULL
        events(STARTED, PASSED, SKIPPED, FAILED)
    }
}

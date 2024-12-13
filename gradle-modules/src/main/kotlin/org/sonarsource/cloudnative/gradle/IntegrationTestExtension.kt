package org.sonarsource.cloudnative.gradle

import groovy.lang.Closure
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.named

interface IntegrationTestExtension {
    /**
     * The directory containing the source files for the integration tests.
     * Will be treated as a task input for the integration test task to check if the task is up-to-date.
     */
    val testSources: DirectoryProperty

    /**
     * Additional configuration for the integration test task.
     */
    fun Project.configureTask(action: Test.() -> Unit) {
        tasks.named<Test>("integrationTest", action)
    }

    /**
     * Additional configuration for the integration test task.
     */
    fun Project.configureTask(action: Closure<*>) {
        tasks.named<Test>("integrationTest") {
            action.call(this)
        }
    }
}

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
package org.sonarsource.cloudnative.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.provider.ProviderFactory

fun Project.signingCondition(): Boolean {
    val branch = System.getenv()["CIRRUS_BRANCH"] ?: ""
    return (branch == "master" || branch.matches("branch-[\\d.]+".toRegex())) &&
        gradle.taskGraph.hasTask(":artifactoryPublish")
}

internal fun RepositoryHandler.repox(
    repository: String,
    providers: ProviderFactory,
    fileOperations: FileOperations,
): MavenArtifactRepository =
    maven {
        name = "artifactory"
        url = fileOperations.uri("https://repox.jfrog.io/repox/$repository")

        // This authentication relies on env vars configured on Cirrus CI or on Gradle properties (`-P<prop>` flags or `gradle.properties` file)
        val artifactoryUsername = providers.environmentVariable("ARTIFACTORY_PRIVATE_USERNAME")
            .orElse(providers.gradleProperty("artifactoryUsername"))
        val artifactoryPassword = providers.environmentVariable("ARTIFACTORY_PRIVATE_PASSWORD")
            .orElse(providers.gradleProperty("artifactoryPassword"))

        if (artifactoryUsername.isPresent && artifactoryPassword.isPresent) {
            authentication {
                credentials {
                    username = artifactoryUsername.get()
                    password = artifactoryPassword.get()
                }
            }
        }
    }

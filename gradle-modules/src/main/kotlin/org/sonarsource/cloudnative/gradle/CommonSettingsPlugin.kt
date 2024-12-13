/*
 * SonarSource Cloud Native Gradle Modules
 * Copyright (C) 2024-2024 SonarSource SA
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

import com.gradle.develocity.agent.gradle.DevelocityConfiguration
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.repositories

open class CommonSettingsPlugin
    @Inject
    constructor(
        private val fileOperations: FileOperations,
    ) : Plugin<Settings> {
        override fun apply(settings: Settings) {
            settings.configureRepositories()

            settings.applyPlugins()

            settings.gradle.allprojects {
                // this value is present on CI
                val buildNumber: String? = System.getProperty("buildNumber")
                val version = properties["version"] as String
                if (version.endsWith("-SNAPSHOT") && buildNumber != null) {
                    val versionSuffix = if (version.count { it == '.' } == 1) ".0.$buildNumber" else ".$buildNumber"
                    project.version = version.replace("-SNAPSHOT", versionSuffix).also {
                        logger.lifecycle("Project ${project.name} version set to $it")
                    }
                }
            }

            settings.configureDevelocity()
        }

        private fun Settings.configureRepositories() {
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                    repox(settings.providers, fileOperations)
                }
            }

            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    repox(settings.providers, fileOperations)
                }
            }

            buildscript.repositories {
                gradlePluginPortal()
            }
        }

        private fun Settings.applyPlugins() {
            // Blowdryer is needed to provide immutable URLs for Spotless (e.g. the Eclipse formatter config)
            settings.pluginManager.apply("com.diffplug.blowdryerSetup")

            settings.pluginManager.apply("com.gradle.develocity")
        }

        private fun RepositoryHandler.repox(
            providers: ProviderFactory,
            fileOperations: FileOperations,
        ): MavenArtifactRepository =
            maven {
                name = "artifactory"
                url = fileOperations.uri("https://repox.jfrog.io/repox/sonarsource")

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

        private fun Settings.configureDevelocity() {
            val develocity = extensions.getByType<DevelocityConfiguration>()

            extensions.configure<DevelocityConfiguration> {
                server = "https://develocity.sonar.build"
                buildScan {
                    tag(if (System.getenv("CI").isNullOrEmpty()) "local" else "CI")
                    tag(System.getProperty("os.name"))
                    if (System.getenv("CIRRUS_BRANCH") == "master") {
                        tag("master")
                    }
                    if (System.getenv("CIRRUS_PR")?.isBlank() == false) {
                        tag("PR")
                    }
                    value("Build Number", System.getenv("BUILD_NUMBER"))
                    value("Branch", System.getenv("CIRRUS_BRANCH"))
                    value("PR", System.getenv("CIRRUS_PR"))
                    value("PR Title", System.getenv("CIRRUS_PR_TITLE"))
                }
            }

            val isCI = System.getenv("CI") != null
            buildCache {
                local {
                    isEnabled = !isCI
                }
                remote(develocity.buildCache) {
                    isEnabled = true
                    isPush = isCI
                }
            }
        }
    }

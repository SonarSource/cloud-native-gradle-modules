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
plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

val kotlinGradleDelimiter = "(package|import|plugins|pluginManagement|dependencyResolutionManagement|repositories) "
spotless {
    kotlinGradle {
        target("*.gradle.kts", "src/**/*.gradle.kts")
        licenseHeaderFile(rootProject.file("LICENSE_HEADER"), kotlinGradleDelimiter).updateYearWithLatest(true)
    }
}

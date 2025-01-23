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
plugins {
    id("org.sonarsource.cloud-native.java-conventions")
    id("org.sonarsource.cloud-native.code-style-conventions")
    id("org.sonarsource.cloud-native.publishing-configuration")
    id("com.gradleup.shadow")
}

val cleanupTask = tasks.register<Delete>("cleanupOldVersion") {
    group = "build"
    description = "Clean up jars of old plugin version"

    delete(
        fileTree(project.layout.buildDirectory.dir("libs")).matching {
            include("${project.name}-*.jar")
            exclude("${project.name}-${project.version}-*.jar")
        }
    )
}

artifacts {
    archives(tasks.shadowJar)
}

tasks.shadowJar {
    dependsOn(cleanupTask)
}

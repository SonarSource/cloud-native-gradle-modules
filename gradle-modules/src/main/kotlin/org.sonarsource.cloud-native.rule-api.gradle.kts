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
import org.sonarsource.cloudnative.gradle.RuleApiExtension
import org.sonarsource.cloudnative.gradle.registerRuleApiGenerateTask
import org.sonarsource.cloudnative.gradle.registerRuleApiUpdateTask
import org.sonarsource.cloudnative.gradle.repox

val ruleApi: Configuration = configurations.create("ruleApi")
val ruleApiExtension = extensions.create<RuleApiExtension>("ruleApi")

repositories {
    repox("sonarsource-private-releases", providers, ruleApiExtension.fileOperations)
    mavenCentral()
}

dependencies {
    ruleApi("com.sonarsource.rule-api:rule-api:2.9.0.4061")
}

project.afterEvaluate {
    val inputs = ruleApiExtension.inputs.get()
    val ruleApiUpdateTasks = mutableSetOf<TaskProvider<JavaExec>>()
    inputs.forEach { (language, sonarpedia) ->
        registerRuleApiUpdateTask(language, file(sonarpedia)).also { ruleApiUpdateTasks.add(it) }
        registerRuleApiGenerateTask(language, file(sonarpedia))
    }

    tasks.register("ruleApiUpdate") {
        description = "Update ALL rules description"
        group = "Rule API"
        ruleApiUpdateTasks.forEach { this.dependsOn(it) }
    }
}

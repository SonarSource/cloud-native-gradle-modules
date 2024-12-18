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

// Register the tasks to generate and update the rules. This is a callback that will be executed when the taskListProvider is evaluated.
// This will be done because taskListProvider will be used in the dependsOn of the umbrella task ruleApiUpdate.
val taskListProvider = ruleApiExtension.languageToSonarpediaDirectory.map {
    it.map { (language, sonarpediaDirectory) ->
        // Register the task to generate a new rule, discard the returned provider
        registerRuleApiGenerateTask(language, file(sonarpediaDirectory))
        // Register the task to update the rule, and return the provider to be used as a dependency of the umbrella ruleApiUpdate task
        registerRuleApiUpdateTask(language, file(sonarpediaDirectory))
    }
}

tasks.register("ruleApiUpdate") {
    description = "Update ALL rules description"
    group = "Rule API"

    dependsOn(taskListProvider)
}

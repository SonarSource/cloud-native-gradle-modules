# cloud-native-gradle-modules

Common Gradle modules for multiple projects

# Usage in a project
This repository is supposed to be used as a submodule in a project. The Gradle build should then be configured as an include build.

To include this repository as a submodule in a project, run the following command in the project's root directory:
```bash
git submodule add https://github.com/SonarSource/cloud-native-gradle-modules gradle/build-logic
```

Then, in the project's `settings.gradle.kts` file, add `includeBuild` to the `pluginManagement` block:
```kotlin
pluginManagement {
    includeBuild("gradle/build-logic")
}
```

Here we used `gradle/build-logic` as a directory, but the choice is arbitrary.
An example usage can be seen in the [Sonar-Go analyzer](https://github.com/SonarSource/sonar-go/blob/d4b923d43c3183927a32dc0956cbf4e4ec50d8a9/settings.gradle.kts#L17)

### Configure Git to Automatically Checkout Changes in the Submodule

When a newer version of the submodule is integrated into a remote branch, running `git pull` will not automatically update the submodule. Instead, Git will display it as changed, and `git status` will show a message like:
```text
modified:   gradle/build-logic (new commits)
```

To configure Git to automatically checkout changes in the submodule, run the following command:

```bash
git config submodule.recurse true
```

Optionally, run this command with `--global` to apply this configuration globally.

/*
 * SonarSource Cloud Native Gradle Modules
 * Copyright (C) 2024-2026 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
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

import com.github.jk1.license.LicenseFileDetails
import com.github.jk1.license.ModuleData
import com.github.jk1.license.ProjectData
import com.github.jk1.license.render.ReportRenderer
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayList
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider

private const val APACHE_LICENSE_FILE_NAME: String = "Apache-2.0.txt"
private const val MIT_FILE_NAME: String = "MIT.txt"

val LICENSE_TITLE_TO_RESOURCE_FILE: Map<String, String> = buildMap {
    put("Apache License, Version 2.0", APACHE_LICENSE_FILE_NAME)
    put("Apache License Version 2.0", APACHE_LICENSE_FILE_NAME)
    put("The Apache License, Version 2.0", APACHE_LICENSE_FILE_NAME)
    put("Apache 2", APACHE_LICENSE_FILE_NAME)
    put("Apache-2.0", APACHE_LICENSE_FILE_NAME)
    put("Apache 2.0", APACHE_LICENSE_FILE_NAME)
    put("The Apache Software License, Version 2.0", APACHE_LICENSE_FILE_NAME)
    put("BSD-3-Clause", "BSD-3.txt")
    put("BSD", "BSD-2.txt")
    put("GWT Terms", APACHE_LICENSE_FILE_NAME) // See https://www.gwtproject.org/terms.html
    put("GNU LGPL 3", "GNU-LGPL-3.txt")
    put("Go License", "Go.txt")
    put("MIT License", MIT_FILE_NAME)
    put("MIT", MIT_FILE_NAME)
    put("Bouncy Castle Licence", MIT_FILE_NAME)
    put("0BSD", "0BSD.txt")
    put("GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1", "lgpl-2.1.txt")
}

class AnalyzerLicensingPackagingRenderer(
    private val buildOutputDir: Path,
    private val dependencyLicenseOverrides: Provider<Map<String, java.io.File>>,
) : ReportRenderer {
    private val logger = Logging.getLogger(AnalyzerLicensingPackagingRenderer::class.java)
    private lateinit var generatedLicenseResourcesDirectory: Path
    private val dependenciesWithUnusableLicenseFileInside: Set<String> = setOf(
        "com.fasterxml.jackson.dataformat:jackson-dataformat-smile",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml",
        "com.fasterxml.woodstox:woodstox-core",
        "org.codehaus.woodstox:stax2-api"
    )
    private val exceptions: ArrayList<String> = ArrayList()

    // Generate license files for all dependencies in the licenses folder
    override fun render(data: ProjectData) {
        generatedLicenseResourcesDirectory = buildOutputDir.resolve("licenses")
        try {
            generateDependencyFiles(data)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        if (exceptions.isNotEmpty()) {
            val exceptionLog = exceptions.joinToString(separator = "\n")
            throw RuntimeException("Exceptions occurred during license file generation:\n$exceptionLog")
        }
    }

    @Throws(IOException::class, URISyntaxException::class)
    private fun generateDependencyFiles(data: ProjectData) {
        for (dependency in data.allDependencies) {
            generateDependencyFile(dependency)
        }
    }

    /**
     * Generate a license file for a given dependency.
     * First we try to copy a configured override in `copyOverriddenLicense`.
     * If there is no override, we try to copy the license file included in the dependency itself
     * in `copyIncludedLicenseFromDependency`.
     * If there is no included license file, or the dependency contains an unusable one,
     * we derive the license from the pom in `findLicenseIdentifierInPomAndCopyFromResources`.
     * That method looks up the license identifier and copies the corresponding file from our resources,
     * using the mapping defined in `LICENSE_TITLE_TO_RESOURCE_FILE`.
     */
    @Throws(IOException::class, URISyntaxException::class)
    private fun generateDependencyFile(data: ModuleData) {
        val copyOverrideLicenseFile = copyOverriddenLicense(data)
        if (copyOverrideLicenseFile.success) {
            return
        }

        val copyIncludedLicenseFile = copyIncludedLicenseFromDependency(data)
        if (copyIncludedLicenseFile.success) {
            return
        }

        val copyFromResources = findLicenseIdentifierInPomAndCopyFromResources(data)
        if (copyFromResources.success) {
            return
        }

        exceptions.add("${data.group}.${data.name}: ${copyOverrideLicenseFile.message}")
        exceptions.add("${data.group}.${data.name}: ${copyIncludedLicenseFile.message}")
        exceptions.add("${data.group}.${data.name}: ${copyFromResources.message}")
    }

    @Throws(IOException::class)
    private fun copyOverriddenLicense(data: ModuleData): Status {
        val dependencyKey = "${data.group}:${data.name}"
        val overrideFile = dependencyLicenseOverrides.getOrElse(emptyMap())[dependencyKey]
            ?: return Status.failure("No override configured.")
        copyLicenseFile(data, overrideFile.toPath())
        logger.info("{}: used configured override '{}'", dependencyKey, overrideFile.name)
        return Status.success
    }

    @Throws(IOException::class)
    private fun copyIncludedLicenseFromDependency(data: ModuleData): Status {
        val dependencyKey = "${data.group}:${data.name}"
        if (dependenciesWithUnusableLicenseFileInside.contains(dependencyKey)) {
            return Status.failure("Skipped packaged license because this dependency is on the unusable-license list.")
        }

        val licenseFileDetails = data.licenseFiles.stream().flatMap { licenseFile -> licenseFile.fileDetails.stream() }
            .filter { file: LicenseFileDetails -> file.file.contains("LICENSE") }
            .findFirst()

        if (licenseFileDetails.isEmpty) {
            return Status.failure("No license file data found.")
        }

        copyLicenseFile(data, buildOutputDir.resolve(licenseFileDetails.get().file))
        logger.info("{}: copied packaged license '{}'", dependencyKey, licenseFileDetails.get().file)
        return Status.success
    }

    @Throws(IOException::class, URISyntaxException::class)
    private fun findLicenseIdentifierInPomAndCopyFromResources(data: ModuleData): Status {
        val pomLicense = data.poms.stream().flatMap { pomData -> pomData.licenses.stream() }
            .findFirst()

        if (pomLicense.isEmpty) {
            return Status.failure("No license found in pom data.")
        }

        return copyLicenseFromResources(data, pomLicense.get().name)
    }

    @Throws(IOException::class)
    private fun copyLicenseFile(
        data: ModuleData,
        fileToCopy: Path,
    ): Status {
        // Modify to use LF line endings
        val normalizedFile = Files.readAllLines(fileToCopy).joinToString("\n")
        Files.write(
            generateLicensePath(data),
            normalizedFile.toByteArray(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
        return Status.success
    }

    @Throws(IOException::class)
    private fun copyLicenseFromResources(
        data: ModuleData,
        licenseName: String,
    ): Status {
        val licenseResourceFileName = LICENSE_TITLE_TO_RESOURCE_FILE[licenseName]
            ?: return Status.failure("License file '$licenseName' could not be found.")
        copyLicenseResourceByFileName(data, licenseResourceFileName)
        logger.info(
            "{}: used bundled resource '{}' for POM license '{}'",
            "${data.group}:${data.name}",
            licenseResourceFileName,
            licenseName
        )
        return Status.success
    }

    @Throws(IOException::class)
    private fun copyLicenseResourceByFileName(
        data: ModuleData,
        resourceFileName: String,
    ): Status {
        val resourceAsStream = AnalyzerLicensingPackagingRenderer::class.java.getResourceAsStream("/licenses/$resourceFileName")
            ?: throw IOException("Resource not found for license: $resourceFileName")
        Files.copy(resourceAsStream, generateLicensePath(data), StandardCopyOption.REPLACE_EXISTING)
        return Status.success
    }

    private fun generateLicensePath(data: ModuleData): Path =
        generatedLicenseResourcesDirectory.resolve("${data.group}.${data.name}-LICENSE.txt")

    private data class Status(
        val success: Boolean,
        val message: String?,
    ) {
        companion object {
            var success: Status = Status(true, null)

            fun failure(message: String?): Status = Status(false, message)
        }
    }
}

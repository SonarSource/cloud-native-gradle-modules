/*
 * SonarSource Cloud Native Gradle Modules
 * Copyright (C) 2024-2025 SonarSource Sàrl
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
import java.io.File
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.ArrayList

class AnalyzerLicensingPackagingRenderer(
    private val buildOutputDir: String,
) : ReportRenderer {
    private var apacheLicenseFileName: String = "Apache-2.0.txt"

    private var generatedLicenseResourcesDirectory: File? = null
    private val licenseTitleToResourceFile: MutableMap<String, String> = HashMap()
    private val librariesWithCustomBehavior: MutableSet<String>
    private val exceptions: ArrayList<String> = ArrayList()

    init {
        licenseTitleToResourceFile["Apache License, Version 2.0"] = apacheLicenseFileName
        licenseTitleToResourceFile["Apache License Version 2.0"] = apacheLicenseFileName
        licenseTitleToResourceFile["Apache 2"] = apacheLicenseFileName
        licenseTitleToResourceFile["Apache-2.0"] = apacheLicenseFileName
        licenseTitleToResourceFile["The Apache Software License, Version 2.0"] = apacheLicenseFileName
        licenseTitleToResourceFile["BSD-3-Clause"] = "BSD-3.txt"
        licenseTitleToResourceFile["GNU LGPL 3"] = "GNU-LGPL-3.txt"
        licenseTitleToResourceFile["Go License"] = "Go.txt"
        librariesWithCustomBehavior = HashSet()
        librariesWithCustomBehavior.add("com.fasterxml.jackson.dataformat.jackson-dataformat-smile")
        librariesWithCustomBehavior.add("com.fasterxml.jackson.dataformat.jackson-dataformat-yaml")
    }

    override fun render(data: ProjectData) {
        generatedLicenseResourcesDirectory = File(buildOutputDir, "licenses")
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

    @Throws(IOException::class, URISyntaxException::class)
    private fun generateDependencyFile(data: ModuleData) {
        val copyIncludedLicenseFile = copyIncludedLicenseFromDependency(data)
        if (copyIncludedLicenseFile.success) {
            return
        }

        val copyFromResources = findLicenseIdentifierInPomAndCopyFromResources(data)
        if (copyFromResources.success) {
            return
        }

        exceptions.add("${data.group}.${data.name}: ${copyIncludedLicenseFile.message}")
        exceptions.add("${data.group}.${data.name}: ${copyFromResources.message}")
    }

    @Throws(IOException::class)
    private fun copyIncludedLicenseFromDependency(data: ModuleData): Status {
        if (librariesWithCustomBehavior.contains("${data.group}.${data.name}")) {
            return Status.failure("Excluded copying license from dependency as it's not the right one.")
        }

        val licenseFileDetails = data.licenseFiles.stream().findFirst().map { licenseFile ->
            licenseFile.fileDetails.stream()
                .filter { file: LicenseFileDetails -> file.file.contains("LICENSE") }
                .findFirst().orElse(null)
        }

        if (licenseFileDetails.isEmpty) {
            return Status.failure("No license file data found.")
        }

        copyLicenseFile(data, Path.of(buildOutputDir, licenseFileDetails.get().file))
        return Status.success
    }

    @Throws(IOException::class, URISyntaxException::class)
    private fun findLicenseIdentifierInPomAndCopyFromResources(data: ModuleData): Status {
        val pomLicense = data.poms.stream().findFirst().map { pomData ->
            pomData.licenses.stream()
                .findFirst().orElse(null)
        }

        if (pomLicense.isEmpty) {
            return Status.failure("No license found in pom data.")
        }

        copyLicenseFromResources(data, pomLicense.get().name)
        return Status.success
    }

    @Throws(IOException::class)
    private fun copyLicenseFile(
        data: ModuleData,
        fileToCopy: Path,
    ): Status {
        // Make sure the file uses Unix line endings
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
        val licenseResourceFileName = licenseTitleToResourceFile[licenseName]
        val resourceAsStream = AnalyzerLicensingPackagingRenderer::class.java.getResourceAsStream("/licenses/$licenseResourceFileName")
            ?: throw IOException("Resource not found for license: $licenseName")
        Files.copy(resourceAsStream, generateLicensePath(data), StandardCopyOption.REPLACE_EXISTING)
        return Status.success
    }

    private fun generateLicensePath(data: ModuleData): Path =
        Path.of(generatedLicenseResourcesDirectory!!.path, "${data.group}.${data.name}-LICENSE.txt")

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

package com.fwcd.ktda.classpath

import com.fwcd.ktda.LOG
import com.fwcd.ktda.util.firstNonNull
import com.fwcd.ktda.util.tryResolving
import com.fwcd.ktda.util.execAndReadStdout
import com.fwcd.ktda.util.KotlinDAException
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

fun readBuildGradle(buildFile: Path): Set<Path> {
    val projectDirectory = buildFile.getParent()

    // The first successful dependency resolver is used
    // (evaluating them from top to bottom)
    var dependencies = firstNonNull<Set<Path>>(
        { tryResolving("dependencies using Gradle dependencies CLI") { readDependenciesViaGradleCLI(projectDirectory) } }
    ).orEmpty()

    if (dependencies.isEmpty()) {
        LOG.warning("Could not resolve Gradle dependencies using any resolution strategy!")
    }

    return dependencies
}

private fun createTemporaryGradleFile(): File {
    val config = File.createTempFile("classpath", ".gradle")
    config.deleteOnExit()
    
    LOG.info("Creating temporary gradle file ${config.absolutePath}")

    config.bufferedWriter().use { configWriter ->
        ClassLoader.getSystemResourceAsStream("classpathFinder.gradle").bufferedReader().use { configReader ->
            configReader.copyTo(configWriter)
        }
    }

    return config
}

private fun getGradleCommand(workspace: Path): Path {
    val wrapperName = if (isOSWindows()) "gradlew.bat" else "gradlew"
    val wrapper = workspace.resolve(wrapperName).toAbsolutePath()
    if (Files.exists(wrapper)) {
        return wrapper
    } else {
        return findCommandOnPath("gradle") ?: throw KotlinDAException("Could not find 'gradle' on PATH")
    }
}

private fun readDependenciesViaGradleCLI(projectDirectory: Path): Set<Path>? {
    LOG.info("Attempting dependency resolution through CLI...")
    val config = createTemporaryGradleFile()
    val gradle = getGradleCommand(projectDirectory)
    val cmd = "$gradle -I ${config.absolutePath} kotlinLSPDeps --console=plain"
    LOG.fine("  -- executing $cmd")
    val dependencies = findGradleCLIDependencies(cmd, projectDirectory)
    return dependencies
}

private fun findGradleCLIDependencies(command: String, projectDirectory: Path): Set<Path>? {
    val result = execAndReadStdout(command, projectDirectory)
    LOG.fine(result)
    return parseGradleCLIDependencies(result)
}

private val artifactPattern by lazy { "kotlin-lsp-gradle (.+)(\r?\n)".toRegex() }

private fun parseGradleCLIDependencies(output: String): Set<Path>? {
    val artifacts = artifactPattern.findAll(output)
        .mapNotNull { FileSystems.getDefault().getPath(it.groups[1]?.value) }
        .filterNotNull()
    return artifacts.toSet()
}

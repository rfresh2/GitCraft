import org.jetbrains.gradle.ext.settings
import org.jetbrains.gradle.ext.taskTriggers
import java.net.URL
import java.nio.file.Files

val externalDependenciesDir = layout.buildDirectory.dir("dependencies")

plugins {
	`java-library`
	id("org.jetbrains.gradle.plugin.idea-ext") version "1.+"
}

repositories {
	mavenCentral()
	maven {
		name = "Fabric"
		url = uri("https://maven.fabricmc.net/")
	}
}

dependencies {
	val externalJars = externalDependenciesDir.get().asFile.takeIf { it.exists() }?.listFiles()?.toList() ?: emptyList()
	if (externalJars.isNotEmpty()) {
		implementation(files(externalJars))
	}
}

val downloadJarsTask = tasks.register("downloadJars") {
	group = "build"
	doLast {
		val jsonFile = file("dependencies.json")
		if (!jsonFile.exists()) {
			throw GradleException("dependencies.json file not found at ${jsonFile.absolutePath}")
		}

		// Read the JSON file as a plain string
		val jsonContent = jsonFile.readText()

		// Extract URLs manually
		val jarUrls = Regex("\"url\":\\s*\"(https?://[^\"]+)\"")
			.findAll(jsonContent)
			.map { it.groupValues[1] }
			.toList()

		val targetDir = externalDependenciesDir.get().asFile
		if (!targetDir.exists()) targetDir.mkdirs()

		jarUrls.forEach { jarUrl ->
			val fileName = jarUrl.substringAfterLast("/")
			val targetFile = targetDir.resolve(fileName)

			if (!targetFile.exists()) {
				println("Downloading $fileName...")
				URL(jarUrl).openStream().use { input ->
					Files.copy(input, targetFile.toPath())
				}
				println("Saved to ${targetFile.absolutePath}")
			} else {
				println("$fileName already exists, skipping.")
			}
		}
	}
}

idea {
	project.settings.taskTriggers.beforeSync(downloadJarsTask)
}

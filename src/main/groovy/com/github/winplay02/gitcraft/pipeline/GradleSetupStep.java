package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.RepoWrapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GradleSetupStep extends Step {

	private final Path rootPath;

	public GradleSetupStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	public GradleSetupStep() {
		this(GitCraftPaths.GRADLE_SETUP);
	}

	@Override
	public String getName() {
		return STEP_GRADLE_SETUP;
	}

	@Override
	public StepResult run(final PipelineCache pipelineCache, final OrderedVersion mcVersion, final MappingFlavour mappingFlavour, final MinecraftVersionGraph versionGraph, final RepoWrapper repo) throws Exception {
		copyGradleResource("settings.gradle.kts", rootPath.resolve("settings.gradle.kts"));
		copyGradleResource("gradlew.bat", rootPath.resolve("gradlew.bat"));
		copyGradleResource("gradlew", rootPath.resolve("gradlew"));
		copyGradleResource("build.gradle.kts", rootPath.resolve("build.gradle.kts"));
		copyGradleResource("gitignore", rootPath.resolve(".gitignore"));
		copyGradleResource("gradle/wrapper/gradle-wrapper.properties", rootPath.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties"));
		copyGradleResource("gradle/wrapper/gradle-wrapper.jar", rootPath.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"));
		copyGradleResource("gradle.properties", rootPath.resolve("gradle.properties"));
		replaceInFile(rootPath.resolve("gradle.properties"), "${minecraft_version}", mcVersion.launcherFriendlyVersionName());
		return StepResult.SUCCESS;
	}

	protected Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return rootPath;
	}

	protected void copyGradleResource(String path, Path targetPath) {
		targetPath.getParent().toFile().mkdirs();
		try (var stream = getClass().getClassLoader().getResourceAsStream("gradle-template/" + path)) {
			if (stream == null) {
				throw new UncheckedIOException(new IOException("Resource not found: " + path));
			}
			Files.copy(stream, targetPath, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected void replaceInFile(Path filePath, String target, String replacement) {
		try {
			String content = Files.readString(filePath);
			content = content.replace(target, replacement);
			Files.writeString(filePath, content);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}

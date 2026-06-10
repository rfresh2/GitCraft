package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.Mapping;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import daomephsta.unpick.api.ConstantUninliner;
import daomephsta.unpick.api.classresolvers.ClassResolvers;
import daomephsta.unpick.api.classresolvers.IClassResolver;
import daomephsta.unpick.api.constantgroupers.ConstantGroupers;
import net.fabricmc.loom.util.FileSystemUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UnpickStep extends Step {
	private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("gitcraft-unpick");

	private final Path rootPath;

	public UnpickStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	public UnpickStep() {
		this(GitCraftPaths.REMAPPED);
	}

	@Override
	public String getName() {
		return STEP_UNPICK;
	}

	@Override
	protected Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return this.rootPath.resolve(String.format("%s-%s-unpicked.jar", mcVersion.launcherFriendlyVersionName(), mappingFlavour.toString()));
	}

	@Override
	public boolean preconditionsShouldRun(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) {
		if (!mcVersion.requiresRemapping()) {
			return false;
		}
		Map<String, Path> additionalMappingPaths = mappingFlavour.getMappingImpl().getAdditionalMappingInformation(mcVersion);
		return mappingFlavour.getMappingImpl().supportsConstantUnpicking() && additionalMappingPaths.containsKey(Mapping.KEY_UNPICK_CONSTANTS) && additionalMappingPaths.containsKey(Mapping.KEY_UNPICK_DEFINITIONS) && super.preconditionsShouldRun(pipelineCache, mcVersion, mappingFlavour, versionGraph, repo);
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws Exception {
		Path unpickedPath = getInternalArtifactPath(mcVersion, mappingFlavour);
		if (Files.exists(unpickedPath) && Files.size(unpickedPath) > 22 /* not empty jar */) {
			return StepResult.UP_TO_DATE;
		}
		if (Files.exists(unpickedPath)) {
			Files.delete(unpickedPath);
		}
		Path libraryRoot = pipelineCache.getForKey(Step.STEP_FETCH_LIBRARIES);
		Path remappedPath = pipelineCache.getForKey(Step.STEP_REMAP);
		// if remapping did not happen, nothing can be done here
		if (remappedPath == null) {
			MiscHelper.panic("A remapped JAR for version %s does not exist", mcVersion.launcherFriendlyVersionName());
		}
		Map<String, Path> additionalPaths = mappingFlavour.getMappingImpl().getAdditionalMappingInformation(mcVersion);
		var libraries = mcVersion.libraries().stream()
				.map(artifact -> artifact.resolve(libraryRoot))
				.toList();
		unpickJar(remappedPath, unpickedPath, additionalPaths.get(Mapping.KEY_UNPICK_DEFINITIONS), additionalPaths.get(Mapping.KEY_UNPICK_CONSTANTS), libraries);
		return StepResult.SUCCESS;
	}

	private static void unpickJar(Path inputJar, Path outputJar, Path unpickDefinitions, Path constantsJar, List<Path> libraries) throws IOException {
		var classpath = Stream.of(
						Stream.of(inputJar),
						Stream.of(constantsJar),
						libraries.stream()
				)
				.flatMap(s -> s)
				.toList();

		try (var classpathFs = ZipFsClasspath.create(classpath);
				var definitions = Files.newInputStream(unpickDefinitions);
				var inputFs = FileSystemUtil.getJarFileSystem(inputJar, false);
				var outputFs = FileSystemUtil.getJarFileSystem(outputJar, true)) {
			var classResolver = classpathFs.createClassResolver().chain(ClassResolvers.classpath());
			var uninliner = ConstantUninliner.builder()
					.logger(LOGGER)
					.classResolver(classResolver)
					.grouper(ConstantGroupers.dataDriven()
							.logger(LOGGER)
							.lenient(true)
							.classResolver(classResolver)
							.mappingSource(definitions)
							.build())
					.build();

			copyUnpickedEntries(inputFs.getRoot(), outputFs.getRoot(), uninliner);
		}
	}

	private static void copyUnpickedEntries(Path inputRoot, Path outputRoot, ConstantUninliner uninliner) throws IOException {
		try (var stream = Files.walk(inputRoot)) {
			var paths = stream.sorted(Comparator.comparing(Path::toString)).collect(Collectors.toList());

			for (var input : paths) {
				var output = outputRoot.resolve(inputRoot.relativize(input).toString());

				if (Files.isDirectory(input)) {
					Files.createDirectories(output);
					continue;
				}

				var parent = output.getParent();

				if (parent != null) {
					Files.createDirectories(parent);
				}

				if (!input.toString().endsWith(".class")) {
					Files.copy(input, output);
					continue;
				}

				var classNode = new ClassNode();

				try (var is = Files.newInputStream(input)) {
					var reader = new ClassReader(is);
					reader.accept(classNode, 0);
				}

				uninliner.transform(classNode);

				var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				classNode.accept(writer);

				Files.write(output, writer.toByteArray());
			}
		}
	}

	private record ZipFsClasspath(List<FileSystemUtil.Delegate> fileSystems) implements Closeable {
		private static ZipFsClasspath create(List<Path> classpath) throws IOException {
			var fileSystems = new ArrayList<FileSystemUtil.Delegate>();

			for (var path : classpath) {
				fileSystems.add(FileSystemUtil.getJarFileSystem(path, false));
			}

			return new ZipFsClasspath(fileSystems);
		}

		private IClassResolver createClassResolver() {
			IClassResolver resolver = ClassResolvers.fromDirectory(fileSystems.get(0).getRoot());

			for (int i = 1; i < fileSystems.size(); i++) {
				resolver = resolver.chain(ClassResolvers.fromDirectory(fileSystems.get(i).getRoot()));
			}

			return resolver;
		}

		@Override
		public void close() throws IOException {
			IOException exception = null;

			for (var fileSystem : fileSystems) {
				try {
					fileSystem.close();
				} catch (IOException e) {
					if (exception == null) {
						exception = e;
					} else {
						exception.addSuppressed(e);
					}
				}
			}

			if (exception != null) {
				throw exception;
			}
		}
	}
}

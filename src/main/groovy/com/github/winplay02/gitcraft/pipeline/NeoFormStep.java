package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.GitCraft;
import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.neoform.NeoFormVersions;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loom.util.FileSystemUtil;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Produces recompilable sources using NeoForm, by running the NeoForm Runtime (NFRT) CLI.
 * <p>
 * Replaces the remap, unpick and decompile steps for versions that NeoForm supports.
 * NeoForm only produces Mojang mappings; Parchment parameter names and javadocs are applied on top when available.
 */
public class NeoFormStep extends Step {

	private static final String DIST = "joined";

	private final Path rootPath;
	private final Set<String> warnedVersions = ConcurrentHashMap.newKeySet();

	public NeoFormStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	public NeoFormStep() {
		this(GitCraftPaths.NEOFORM);
	}

	@Override
	public String getName() {
		return STEP_NEOFORM;
	}

	@Override
	protected Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return GitCraftPaths.DECOMPILED_WORKINGS.resolve(String.format("%s-%s-neoform.jar", mcVersion.launcherFriendlyVersionName(), mappingFlavour));
	}

	public static boolean supportsMappings(MappingFlavour mappingFlavour) {
		return mappingFlavour == MappingFlavour.MOJMAP || mappingFlavour == MappingFlavour.MOJMAP_PARCHMENT;
	}

	/**
	 * Whether NeoForm is used to produce the sources of this version.
	 * If not, the regular remap/decompile pipeline is used instead.
	 */
	public boolean appliesTo(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		if (!GitCraft.config.useNeoForm || mappingFlavour == null) {
			return false;
		}
		if (!supportsMappings(mappingFlavour)) {
			warnOnce(mcVersion, "WARNING: NeoForm does not support %s mappings. Using the regular decompiler for %s.", mappingFlavour, mcVersion.launcherFriendlyVersionName());
			return false;
		}
		if (NeoFormVersions.getNeoFormVersion(mcVersion).isEmpty()) {
			warnOnce(mcVersion, "WARNING: No NeoForm release exists for %s. Using the regular decompiler; this version will not be recompilable.", mcVersion.launcherFriendlyVersionName());
			return false;
		}
		return true;
	}

	private void warnOnce(OrderedVersion mcVersion, String format, Object... args) {
		if (this.warnedVersions.add(mcVersion.launcherFriendlyVersionName())) {
			MiscHelper.println(format, args);
		}
	}

	@Override
	public boolean preconditionsShouldRun(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) {
		return appliesTo(mcVersion, mappingFlavour) && super.preconditionsShouldRun(pipelineCache, mcVersion, mappingFlavour, versionGraph, repo);
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws Exception {
		Path outputPath = getInternalArtifactPath(mcVersion, mappingFlavour);
		if (Files.exists(outputPath) && Files.size(outputPath) > 22 /* not empty jar */) {
			return StepResult.UP_TO_DATE;
		}
		Files.deleteIfExists(outputPath);

		String neoFormVersion = NeoFormVersions.getNeoFormVersion(mcVersion).orElseThrow();
		Path neoFormZip = fetchNeoForm(neoFormVersion);
		NeoFormConfigInfo configInfo = readConfig(neoFormZip);
		int currentJava = Runtime.version().feature();
		if (currentJava < configInfo.requiredJavaVersion()) {
			MiscHelper.panic("NeoForm %s needs Java %s to run its tools, but GitCraft is running on Java %s. Run GitCraft with a newer JDK.", neoFormVersion, configInfo.requiredJavaVersion(), currentJava);
		}
		Path nfrtJar = fetchNeoFormRuntime();

		Path rawSources = this.rootPath.resolve("work").resolve(String.format("%s-%s-nfrt-sources.jar", mcVersion.launcherFriendlyVersionName(), mappingFlavour));
		Path logFile = this.rootPath.resolve("logs").resolve(String.format("%s-%s.log", mcVersion.launcherFriendlyVersionName(), mappingFlavour));
		Files.createDirectories(rawSources.getParent());
		Files.createDirectories(logFile.getParent());
		Files.deleteIfExists(rawSources);

		// NFRT sizes the decompiler heap as 256M * (cores of the NFRT JVM); raise the reported core count to get a bigger heap
		int processorCount = Math.max(Runtime.getRuntime().availableProcessors(), Math.ceilDiv(GitCraft.config.neoFormDecompilerMinMemoryMb, 256));
		List<String> command = new ArrayList<>(List.of(
			Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-XX:ActiveProcessorCount=" + processorCount,
			"-jar", nfrtJar.toAbsolutePath().toString(),
			"run",
			"--dist", DIST,
			"--neoform", neoFormZip.toAbsolutePath().toString(),
			"--home-dir", this.rootPath.resolve("nfrt-home").toAbsolutePath().toString(),
			"--no-color",
			"--no-emojis",
			"--write-result=gameSources:" + rawSources.toAbsolutePath()
		));
		Optional<Path> parchmentData = getParchmentData(mcVersion, mappingFlavour);
		if (parchmentData.isPresent()) {
			command.add("--parchment-data=" + parchmentData.get().toAbsolutePath());
			command.add("--parchment-conflict-prefix=" + GitCraftConfig.NEOFORM_PARCHMENT_CONFLICT_PREFIX);
		}

		MiscHelper.println("Running NeoForm %s via NeoForm Runtime %s%s (log: %s)", neoFormVersion, GitCraftConfig.NEOFORM_RUNTIME_VERSION, parchmentData.isPresent() ? " with Parchment" : "", logFile);
		int exitCode = new ProcessBuilder(command)
			.directory(this.rootPath.toFile())
			.redirectErrorStream(true)
			.redirectOutput(logFile.toFile())
			.start()
			.waitFor();
		if (exitCode != 0 || !Files.exists(rawSources)) {
			printLogTail(logFile);
			MiscHelper.panic("NeoForm Runtime failed for %s (exit code %s). See %s", mcVersion.launcherFriendlyVersionName(), exitCode, logFile);
		}

		// Only keep java sources; resources are handled by the commit step (from the merged jar), like in the regular pipeline
		try (FileSystemUtil.Delegate in = FileSystemUtil.getJarFileSystem(rawSources); FileSystemUtil.Delegate out = FileSystemUtil.getJarFileSystem(outputPath, true)) {
			Path inRoot = in.get().getPath("/");
			Path outRoot = out.get().getPath("/");
			try (Stream<Path> files = Files.walk(inRoot)) {
				for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
					Path target = outRoot.resolve(inRoot.relativize(file).toString());
					Files.createDirectories(target.getParent());
					Files.writeString(target, indentWithTabs(Files.readString(file), configInfo.indent()));
				}
			}
			DecompileStep.writeLibraries(outRoot, mcVersion);
			// read by the gradle setup step, to put NeoForm's extra compile dependencies into the build file
			Files.writeString(outRoot.resolve(NEOFORM_LIBRARIES_FILE), String.join("\n", configInfo.libraries()));
		}
		Files.deleteIfExists(rawSources);
		return StepResult.SUCCESS;
	}

	public static final String NEOFORM_LIBRARIES_FILE = "neoform-libraries.txt";

	/// Vineflower's default, used when NeoForm does not set --indent-string (e.g. 1.20.2 - 1.20.4)
	private static final String VINEFLOWER_DEFAULT_INDENT = "   ";

	/**
	 * NeoForm indents with spaces (3 or 4, depending on the release), the regular decompiler with tabs. Converting keeps diffs between NeoForm and non-NeoForm versions readable.
	 * Leftover spaces (e.g. alignment of continuation lines) are kept. Lines inside text blocks are not touched, as their indentation is part of the string.
	 */
	static String indentWithTabs(String source, String indent) {
		StringBuilder result = new StringBuilder(source.length());
		boolean inTextBlock = false;
		for (String line : source.split("\n", -1)) {
			if (!result.isEmpty()) {
				result.append('\n');
			}
			if (inTextBlock) {
				result.append(line);
			} else {
				int indents = 0;
				while (line.startsWith(indent, indents * indent.length())) {
					indents++;
				}
				result.append("\t".repeat(indents)).append(line, indents * indent.length(), line.length());
			}
			// an odd number of delimiters on a line opens or closes a text block
			int delimiters = (line.length() - line.replace("\"\"\"", "").length()) / 3;
			if (delimiters % 2 == 1) {
				inTextBlock = !inTextBlock;
			}
		}
		return result.toString();
	}

	private Optional<Path> getParchmentData(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		if (mappingFlavour != MappingFlavour.MOJMAP_PARCHMENT || !mcVersion.requiresRemapping()) {
			return Optional.empty();
		}
		if (!GitCraft.MOJANG_PARCHMENT_MAPPINGS.doMappingsExist(mcVersion)) {
			MiscHelper.println("WARNING: Parchment does not exist for %s, NeoForm sources will only use Mojang mappings", mcVersion.launcherFriendlyVersionName());
			return Optional.empty();
		}
		Path json = GitCraft.MOJANG_PARCHMENT_MAPPINGS.getParchmentJsonPath(mcVersion);
		if (!Files.exists(json)) {
			MiscHelper.println("WARNING: Parchment data for %s was not prepared (%s), NeoForm sources will only use Mojang mappings", mcVersion.launcherFriendlyVersionName(), json);
			return Optional.empty();
		}
		return Optional.of(json);
	}

	private Path fetchNeoForm(String neoFormVersion) {
		Path zip = this.rootPath.resolve("neoform").resolve(String.format("neoform-%s.zip", neoFormVersion));
		RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(
			String.format("%s/net/neoforged/neoform/%s/neoform-%s.zip", GitCraftConfig.NEOFORGED_MAVEN, neoFormVersion, neoFormVersion),
			new RemoteHelper.LocalFileInfo(zip, null, "NeoForm config", neoFormVersion));
		return zip;
	}

	private Path fetchNeoFormRuntime() {
		String version = GitCraftConfig.NEOFORM_RUNTIME_VERSION;
		Path jar = this.rootPath.resolve(String.format("neoform-runtime-%s-all.jar", version));
		RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(
			String.format("%s/net/neoforged/neoform-runtime/%s/neoform-runtime-%s-all.jar", GitCraftConfig.NEOFORGED_MAVEN, version, version),
			new RemoteHelper.LocalFileInfo(jar, null, "NeoForm Runtime", version));
		return jar;
	}

	record NeoFormConfigInfo(int requiredJavaVersion, List<String> libraries, String indent) {
	}

	static NeoFormConfigInfo readConfig(Path neoFormZip) throws IOException {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(neoFormZip); Reader reader = Files.newBufferedReader(fs.get().getPath("config.json"))) {
			JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
			int javaVersion = 0;
			String indent = VINEFLOWER_DEFAULT_INDENT;
			if (config.has("functions")) {
				for (Map.Entry<String, JsonElement> function : config.getAsJsonObject("functions").entrySet()) {
					JsonObject functionObject = function.getValue().getAsJsonObject();
					if (functionObject.has("java_version")) {
						javaVersion = Math.max(javaVersion, functionObject.get("java_version").getAsInt());
					}
					if (function.getKey().equals("decompile") && functionObject.has("args")) {
						for (JsonElement arg : functionObject.getAsJsonArray("args")) {
							if (arg.getAsString().startsWith("--indent-string=")) {
								indent = arg.getAsString().substring("--indent-string=".length());
							}
						}
					}
				}
			}
			Set<String> libraries = new HashSet<>();
			if (config.has("libraries") && config.getAsJsonObject("libraries").has(DIST)) {
				config.getAsJsonObject("libraries").getAsJsonArray(DIST).forEach(lib -> libraries.add(lib.getAsString()));
			}
			if (indent.isEmpty() || !indent.isBlank()) {
				MiscHelper.panic("Unexpected NeoForm decompiler indentation '%s' in %s", indent, neoFormZip);
			}
			return new NeoFormConfigInfo(javaVersion, libraries.stream().sorted().toList(), indent);
		}
	}

	private static void printLogTail(Path logFile) {
		try {
			List<String> lines = Files.readAllLines(logFile);
			lines.subList(Math.max(0, lines.size() - 40), lines.size()).forEach(line -> MiscHelper.println("\t%s", line));
		} catch (IOException ignored) {
		}
	}
}

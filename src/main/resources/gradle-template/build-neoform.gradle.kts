import groovy.json.JsonSlurper
import java.util.*

plugins {
	`java-library`
}

val minecraftVersion = project.property("minecraft_version") as String
val minecraftJavaVersion = (project.property("java_version") as String).toInt()

repositories {
	mavenCentral()
	maven("https://maven.neoforged.net/releases") {
		name = "NeoForged"
	}
	maven("https://maven.neoforged.net/mojang-meta") {
		name = "MojangMeta"
		content {
			includeModule("net.neoforged", "minecraft-dependencies")
		}
		metadataSources {
			gradleMetadata()
		}
	}
	maven("https://libraries.minecraft.net/") {
		metadataSources {
			mavenPom()
		}
	}
}

val distributionAttribute = Attribute.of("net.neoforged.distribution", String::class.java)
val operatingSystemAttribute = Attribute.of("net.neoforged.operatingsystem", String::class.java)

configurations.runtimeClasspath {
	attributes {
		attribute(operatingSystemAttribute, currentOs())
	}
}

val serverRuntimeClasspath = configurations.create("serverRuntimeClasspath") {
	isCanBeConsumed = false
	attributes {
		attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
		attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
		attribute(distributionAttribute, "server")
	}
}

// used to download the vanilla jar, launch metadata and assets
val neoFormRuntime = configurations.create("neoFormRuntime") {
	isCanBeConsumed = false
	isTransitive = false
}

dependencies {
	implementation("net.neoforged:minecraft-dependencies:$minecraftVersion") {
		attributes {
			attribute(distributionAttribute, "client")
		}
	}
${neoform_dependencies}

	serverRuntimeClasspath("net.neoforged:minecraft-dependencies:$minecraftVersion")
	neoFormRuntime("net.neoforged:neoform-runtime:${neoform_runtime_version}:all")
}

java {
	toolchain { languageVersion = JavaLanguageVersion.of(minecraftJavaVersion) }
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	options.compilerArgs.add("-nowarn")
	options.isWarnings = false
	// Minecraft is too large for the default compiler heap
	options.isFork = true
	options.forkOptions.memoryMaximumSize = "4g"
}

val minecraftDir = layout.buildDirectory.dir("minecraft")
val vanillaClientJar = minecraftDir.map { it.file("client.jar") }
val versionManifest = minecraftDir.map { it.file("version.json") }
val assetsProperties = minecraftDir.map { it.file("assets.properties") }

fun JavaExec.useNeoFormRuntime(vararg arguments: String) {
	group = "minecraft"
	classpath(neoFormRuntime)
	mainClass = "net.neoforged.neoform.runtime.cli.Main"
	// NFRT needs Java 21+, which can be newer than what this Minecraft version uses
	javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(maxOf(21, minecraftJavaVersion)) }
	args(*arguments, "--no-color", "--no-emojis", "--minecraft-version", minecraftVersion)
	inputs.property("minecraftVersion", minecraftVersion)
}

val downloadMinecraft = tasks.register<JavaExec>("downloadMinecraft") {
	description = "Downloads the vanilla client jar and the version manifest"
	useNeoFormRuntime("download-artifacts")
	outputs.files(vanillaClientJar, versionManifest)
	argumentProviders.add(CommandLineArgumentProvider {
		listOf(
			"--write-artifact=client:" + vanillaClientJar.get().asFile.absolutePath,
			"--write-version-manifest=" + versionManifest.get().asFile.absolutePath,
		)
	})
}

val downloadAssets = tasks.register<JavaExec>("downloadAssets") {
	description = "Downloads the client assets (reuses an installed Minecraft launcher's assets if found)"
	useNeoFormRuntime("download-assets")
	outputs.file(assetsProperties)
	argumentProviders.add(CommandLineArgumentProvider {
		listOf("--write-properties=" + assetsProperties.get().asFile.absolutePath)
	})
}

// Resources from the vanilla jar (pack.png, version.json, data, ...), used as a fallback after src/main/resources
val extractVanillaResources = tasks.register<Sync>("extractVanillaResources") {
	group = "minecraft"
	description = "Extracts non-class files from the vanilla client jar"
	dependsOn(downloadMinecraft)
	from(vanillaClientJar.map { zipTree(it) }) {
		exclude("**/*.class", "META-INF/**")
	}
	into(minecraftDir.map { it.dir("resources") })
}

fun currentOs(): String {
	val name = System.getProperty("os.name").lowercase()
	return when {
		name.contains("win") -> "windows"
		name.contains("mac") || name.contains("darwin") -> "osx"
		else -> "linux"
	}
}

fun rulesAllow(rules: List<Map<String, Any?>>?): Boolean {
	if (rules == null) {
		return true
	}
	var allowed = false
	for (rule in rules) {
		@Suppress("UNCHECKED_CAST")
		val os = rule["os"] as Map<String, String>?
		val osMatches = os == null || (
			(os["name"] == null || os["name"] == currentOs()) &&
				(os["arch"] == null || (os["arch"] == "x86" && System.getProperty("os.arch") in listOf("x86", "i386", "i686"))) &&
				(os["version"] == null || Regex(os["version"]!!).containsMatchIn(System.getProperty("os.version")))
			)
		@Suppress("UNCHECKED_CAST")
		val features = rule["features"] as Map<String, Boolean>?
		val featuresMatch = features == null || features.values.all { !it }
		if (osMatches && featuresMatch) {
			allowed = rule["action"] == "allow"
		}
	}
	return allowed
}

@Suppress("UNCHECKED_CAST")
fun launchArguments(manifest: Map<String, Any?>, type: String, variables: Map<String, String>): List<String> {
	val arguments = (manifest["arguments"] as Map<String, Any?>)[type] as List<Any?>
	val result = mutableListOf<String>()
	for (argument in arguments) {
		val values = when (argument) {
			is String -> listOf(argument)
			is Map<*, *> -> if (rulesAllow(argument["rules"] as List<Map<String, Any?>>?)) {
				when (val value = argument["value"]) {
					is String -> listOf(value)
					else -> value as List<String>
				}
			} else {
				emptyList()
			}
			else -> emptyList()
		}
		result += values.map { value -> Regex("\\$\\{(\\w+)}").replace(value) { variables[it.groupValues[1]] ?: it.value } }
	}
	// Gradle sets the classpath itself
	val classpathIndex = result.indexOf("-cp")
	if (classpathIndex >= 0) {
		result.subList(classpathIndex, classpathIndex + 2).clear()
	}
	return result
}

val log4jConfig = file("gradle/log4j2.xml")

// Launch arguments of the client, read from the version manifest once it is downloaded
class ClientLaunch(val manifest: Map<String, Any?>, val variables: Map<String, String>)

val clientLaunch: Provider<ClientLaunch> = providers.provider {
	@Suppress("UNCHECKED_CAST")
	val manifest = JsonSlurper().parse(versionManifest.get().asFile) as Map<String, Any?>
	val assets = Properties().apply { assetsProperties.get().asFile.reader().use { load(it) } }
	val username = providers.gradleProperty("mc_username").getOrElse("Dev")
	ClientLaunch(manifest, mapOf(
		"natives_directory" to minecraftDir.get().dir("natives").asFile.absolutePath,
		"launcher_name" to "gitcraft",
		"launcher_version" to "1.0",
		"auth_player_name" to username,
		"version_name" to minecraftVersion,
		"game_directory" to file("run/$minecraftVersion/client").absolutePath,
		"assets_root" to assets.getProperty("assets_root"),
		"assets_index_name" to assets.getProperty("asset_index"),
		"auth_uuid" to UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray()).toString(),
		"auth_access_token" to "0",
		"clientid" to "0",
		"auth_xuid" to "0",
		"user_type" to "msa",
		"version_type" to manifest["type"] as String,
	))
}

val runClient = tasks.register<JavaExec>("runClient") {
	group = "minecraft"
	description = "Runs the Minecraft client from the compiled sources"
	dependsOn(downloadAssets, extractVanillaResources)
	classpath(sourceSets.main.map { it.output }, extractVanillaResources, configurations.runtimeClasspath)
	workingDir = file("run/$minecraftVersion/client")
	maxHeapSize = "4G"
	jvmArgs("-Dlog4j.configurationFile=${log4jConfig.absolutePath}")
	// Providers are only read when the task runs, after the manifest and assets were downloaded
	mainClass = clientLaunch.map { it.manifest["mainClass"] as String }
	jvmArgumentProviders.add(CommandLineArgumentProvider { clientLaunch.get().let { launchArguments(it.manifest, "jvm", it.variables) } })
	argumentProviders.add(CommandLineArgumentProvider { clientLaunch.get().let { launchArguments(it.manifest, "game", it.variables) } })
	doFirst {
		workingDir.mkdirs()
		minecraftDir.get().dir("natives").asFile.mkdirs()
	}
}

val runServer = tasks.register<JavaExec>("runServer") {
	group = "minecraft"
	description = "Runs the dedicated server from the compiled sources"
	dependsOn(extractVanillaResources)
	classpath(sourceSets.main.map { it.output }, extractVanillaResources, serverRuntimeClasspath)
	mainClass = "net.minecraft.server.Main"
	workingDir = file("run/$minecraftVersion/server")
	maxHeapSize = "2G"
	jvmArgs("-Dlog4j.configurationFile=${log4jConfig.absolutePath}")
	args("--nogui")
	standardInput = System.`in`
	doFirst {
		workingDir.mkdirs()
		val eulaFile = workingDir.resolve("eula.txt");
		if (!eulaFile.exists()) {
			eulaFile.writeText("eula=true")
		}
	}
}

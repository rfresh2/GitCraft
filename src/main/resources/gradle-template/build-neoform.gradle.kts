plugins {
	`java-library`
}

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

dependencies {
	implementation("net.neoforged:minecraft-dependencies:${project.property("minecraft_version")}") {
		attributes {
			attribute(Attribute.of("net.neoforged.distribution", String::class.java), "client")
		}
	}
	// Extra compile libraries required by NeoForm
${neoform_dependencies}
}

java {
	toolchain { languageVersion = JavaLanguageVersion.of(project.property("java_version") as String) }
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	// NeoForm sources are expected to compile, but they produce a lot of warnings
	options.compilerArgs.add("-nowarn")
	options.isWarnings = false
	// Minecraft is too large for the default compiler heap
	options.isFork = true
	options.forkOptions.memoryMaximumSize = "4g"
}

plugins {
	`java-library`
}

repositories {
	mavenCentral()
	maven("https://maven.fabricmc.net/") {
		name = "Fabric"
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
	implementation("com.google.code.findbugs:jsr305:3.0.2")
	implementation("org.jetbrains:annotations:26.0.2")
	implementation("net.fabricmc:fabric-loader:0.19.5")
}

java {
	toolchain { languageVersion = JavaLanguageVersion.of(project.property("java_version") as String) }
}

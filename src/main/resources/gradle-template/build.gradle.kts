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
	maven {
		name = "MojangMeta"
		url = uri("https://maven.neoforged.net/mojang-meta")
		content {
			includeModule("net.neoforged", "minecraft-dependencies")
		}
	}
	maven {
		url = uri("https://libraries.minecraft.net/")
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
	implementation("net.fabricmc:fabric-loader:0.17.2")
}

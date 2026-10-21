package com.github.winplay02.gitcraft.neoform;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.MiscHelper;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the NeoForm build to use for a given Minecraft version, based on the NeoForged maven metadata.
 * <p>
 * NeoForm versions are named {@code <mc version>-<build>}. Older builds use a timestamp as build
 * ({@code 1.21.1-20240808.144430}), newer ones (26.x onwards) use an increasing number ({@code 26.1.2-1}).
 * The latest build for a Minecraft version is used.
 */
public final class NeoFormVersions {
	private static final Pattern TIMESTAMP_BUILD = Pattern.compile("^(.+)-(\\d{8}\\.\\d{6})$");
	private static final Pattern NUMBERED_BUILD = Pattern.compile("^(.+)-(\\d+)$");

	private static Map<String, String> latestBuilds = null;

	private NeoFormVersions() {
	}

	public static Optional<String> getNeoFormVersion(OrderedVersion mcVersion) {
		return Optional.ofNullable(getLatestBuilds().get(mcVersion.launcherFriendlyVersionName()));
	}

	private static synchronized Map<String, String> getLatestBuilds() {
		if (latestBuilds == null) {
			try {
				latestBuilds = latestBuildPerMinecraftVersion(fetchAllVersions());
			} catch (Exception e) {
				MiscHelper.panicBecause(e, "Could not fetch NeoForm versions from %s", GitCraftConfig.NEOFORM_MAVEN_METADATA);
			}
		}
		return latestBuilds;
	}

	private static List<String> fetchAllVersions() throws Exception {
		Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(GitCraftConfig.NEOFORM_MAVEN_METADATA);
		NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().compile("/metadata/versioning/versions/version").evaluate(document, XPathConstants.NODESET);
		List<String> versions = new ArrayList<>(nodes.getLength());
		for (int i = 0; i < nodes.getLength(); i++) {
			versions.add(nodes.item(i).getTextContent().trim());
		}
		return versions;
	}

	static Map<String, String> latestBuildPerMinecraftVersion(List<String> neoFormVersions) {
		Map<String, ParsedVersion> latest = new HashMap<>();
		for (String version : neoFormVersions) {
			ParsedVersion parsed = ParsedVersion.parse(version);
			if (parsed == null) {
				continue; // e.g. "-SNAPSHOT" builds or very early builds without a build suffix
			}
			latest.merge(parsed.minecraftVersion(), parsed, (a, b) -> ParsedVersion.ORDER.compare(a, b) >= 0 ? a : b);
		}
		Map<String, String> result = new HashMap<>();
		latest.forEach((mc, parsed) -> result.put(mc, parsed.fullVersion()));
		return result;
	}

	record ParsedVersion(String fullVersion, String minecraftVersion, String build, boolean timestamp) {
		static final Comparator<ParsedVersion> ORDER = Comparator
			.comparing((ParsedVersion v) -> !v.timestamp()) // numbered builds are newer than timestamped ones
			.thenComparing(v -> v.timestamp() ? 0L : Long.parseLong(v.build()))
			.thenComparing(ParsedVersion::build);

		static ParsedVersion parse(String version) {
			Matcher matcher = TIMESTAMP_BUILD.matcher(version);
			if (matcher.matches()) {
				return new ParsedVersion(version, matcher.group(1), matcher.group(2), true);
			}
			matcher = NUMBERED_BUILD.matcher(version);
			if (matcher.matches()) {
				return new ParsedVersion(version, matcher.group(1), matcher.group(2), false);
			}
			return null;
		}
	}
}

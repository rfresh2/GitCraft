package com.github.winplay02.gitcraft.types;

public class RepoConfig {
	public boolean create_version_branches = true;
	public boolean sort_json = true;
	public boolean no_external_assets = true;
	public boolean no_datagen_snbt = true;
	public boolean no_datagen_report = true;
	public String override_repo_target = null;
	public String mappings = "mojmap_parchment";
	public String[] versions = new String[0];
}

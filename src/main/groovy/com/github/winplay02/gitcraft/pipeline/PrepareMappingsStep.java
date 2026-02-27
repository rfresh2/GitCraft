package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.RepoWrapper;

public class PrepareMappingsStep extends Step {
	@Override
	public String getName() {
		return STEP_PREPARE_MAPPINGS;
	}

	@Override
	public boolean preconditionsShouldRun(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) {
		return mcVersion.requiresRemapping() && super.preconditionsShouldRun(pipelineCache, mcVersion, mappingFlavour, versionGraph, repo);
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws Exception {
		return mappingFlavour.getMappingImpl().prepareMappings(mcVersion);
	}
}

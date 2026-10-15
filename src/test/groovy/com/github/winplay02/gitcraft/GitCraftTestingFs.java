package com.github.winplay02.gitcraft;

import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GitCraftTestingFs implements BeforeAllCallback, AutoCloseable {

	protected static Path temporaryTestingFsPath = null;
	//protected static FileSystemUtil.Delegate temporaryTestingFs;

	@Override
	public void beforeAll(ExtensionContext extensionContext) throws Exception {
		if (temporaryTestingFsPath == null) {
			GitCraft.config = new GitCraftConfig();
			temporaryTestingFsPath = Files.createTempDirectory("gitcraft");
			//temporaryTestingFs = FileSystemUtil.getJarFileSystem(temporaryTestingFsPath.resolve("testingfs.jar"), true);
			//GitCraftPaths.initializePaths(temporaryTestingFs.getPath("."));
			GitCraftPaths.initializePaths(temporaryTestingFsPath);
			extensionContext.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).put("GitCraftTestingFs", this);
		}
	}

	@Override
	public void close() throws IOException {
		if (temporaryTestingFsPath != null) {
			// temporaryTestingFs.close();
			// Files.delete(temporaryTestingFsPath.resolve("testingfs.jar"));
			MiscHelper.deleteDirectory(temporaryTestingFsPath);
			temporaryTestingFsPath = null;
		}
	}
}

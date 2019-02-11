/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android.exopackage;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.android.exopackage.ExopackageInfo.DexInfo;
import com.facebook.buck.android.exopackage.ExopackageInfo.NativeLibsInfo;
import com.facebook.buck.android.exopackage.ExopackageInfo.ResourcesInfo;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ExopackageSymlinkTreeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  public ProjectWorkspace workspace;

  public ProjectFilesystem filesystem;
  private SourcePathResolver pathResolver;

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new ModuleExoHelperTest(), "exo_symlink_tree", tmpFolder);
    workspace.setUp();
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());

    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
  }

  @Test
  public void layoutSymlinkTree() throws Exception {
    Path outputSymlinkRoot = tmpFolder.newFolder();
    filesystem.mkdirs(outputSymlinkRoot);

    ExopackageInfo exopackageInfo = buildExoInfo(tmpFolder.getRoot());
    ExopackageSymlinkTree.createSymlinkTree(
        "com.example", exopackageInfo, pathResolver, filesystem, outputSymlinkRoot);

    verifyMetadataBasedOutput(
        outputSymlinkRoot.resolve("secondary-dex"), metadataLine -> metadataLine.split(" ")[0]);
    verifyMetadataBasedOutput(
        outputSymlinkRoot.resolve("native-libs").resolve("DummyAbi"),
        metadataLine -> metadataLine.split(" ")[1]);
    verifyMetadataBasedOutput(
        outputSymlinkRoot.resolve("resources"),
        metadataLine -> metadataLine.split(" ")[1] + ".apk");

    // Assert the root metadata file was written correctly
    Path rootMetadataPath = outputSymlinkRoot.resolve("metadata.txt");
    Assert.assertTrue(Files.exists(rootMetadataPath));
    Assert.assertEquals(
        "/data/local/tmp/exopackage/com.example", new String(Files.readAllBytes(rootMetadataPath)));
  }

  private DexInfo buildDexInfo(Path relRoot) {
    return DexInfo.of(
        PathSourcePath.of(filesystem, relRoot.resolve("metadata.txt")),
        PathSourcePath.of(filesystem, relRoot));
  }

  private NativeLibsInfo buildNativeLibsInfo(Path relRoot) {
    return NativeLibsInfo.of(
        PathSourcePath.of(filesystem, relRoot.resolve("metadata.txt")),
        PathSourcePath.of(filesystem, relRoot));
  }

  private ResourcesInfo buildResInfo(Path relRoot) {
    return ResourcesInfo.of(
        ImmutableList.of(
            ExopackagePathAndHash.of(
                PathSourcePath.of(filesystem, relRoot.resolve("res_foo.arsc")),
                PathSourcePath.of(filesystem, relRoot.resolve("res_foo.sha1")))));
  }

  private ExopackageInfo buildExoInfo(Path relRoot) {
    return ExopackageInfo.builder()
        .setDexInfo(buildDexInfo(relRoot.resolve("dex")))
        .setNativeLibsInfo(buildNativeLibsInfo(relRoot.resolve("so")))
        .setResourcesInfo(buildResInfo(relRoot.resolve("res")))
        .build();
  }

  /** Read a metadata.txt manifest file and ensure the symlinks that it references were created */
  private void verifyMetadataBasedOutput(Path root, Function<String, String> filenameExtractor)
      throws Exception {
    Path metadataPath = root.resolve("metadata.txt");
    Assert.assertTrue(Files.exists(metadataPath));
    for (String line : Files.readAllLines(metadataPath)) {
      String filename = filenameExtractor.apply(line);
      Path expectedPath = root.resolve(filename);
      Assert.assertTrue("Assert Exists Failed: " + expectedPath, Files.exists(expectedPath));
    }
  }
}

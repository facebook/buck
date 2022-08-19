/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.exopackage;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map.Entry;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ModuleExoHelperTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ModuleExoHelper moduleExoHelper;

  @Before
  public void setUp() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new ModuleExoHelperTest(), "modular_exo", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    Path moduleOutputPath = Paths.get("module_name");
    Path metadataOutputPath = moduleOutputPath.resolve("metadata");

    moduleExoHelper =
        new ModuleExoHelper(
            filesystem,
            ImmutableList.of(
                IsolatedExopackageInfo.IsolatedDexInfo.of(
                    AbsPath.of(filesystem.resolve(metadataOutputPath)),
                    AbsPath.of(filesystem.resolve(moduleOutputPath)))));
  }

  @Test
  public void testGetFilesToInstall() throws Exception {
    ImmutableMap<Path, Path> filesToInstall = moduleExoHelper.getFilesToInstall();
    assertThat(filesToInstall, Matchers.aMapWithSize(1));
    Entry<Path, Path> entry = filesToInstall.entrySet().iterator().next();
    Path destPath = entry.getKey();
    Path sourcePath = entry.getValue();
    assertThat(
        destPath.toString(), Matchers.startsWith(ModuleExoHelper.MODULAR_DEX_DIR.toString()));
    assertThat(
        sourcePath.toString(),
        Matchers.endsWith(Paths.get("module_name", "secondary.jar").toString()));
  }

  @Test
  public void testGetMetadataToInstall() throws Exception {
    ImmutableMap<Path, String> metadataToInstall = moduleExoHelper.getMetadataToInstall();
    assertThat(metadataToInstall, Matchers.aMapWithSize(2));
    String contents =
        metadataToInstall.get(ModuleExoHelper.MODULAR_DEX_DIR.resolve("metadata.txt"));
    Assert.assertNotNull(contents);

    // Check that the metadata lists the destination name of the jar
    assertThat(contents, Matchers.startsWith("module-module_name.dex.jar "));
  }
}

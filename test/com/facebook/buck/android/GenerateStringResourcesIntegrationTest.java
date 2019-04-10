/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GenerateStringResourcesIntegrationTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private ProjectFilesystem filesystem;

  private static final String AAPT1_BUILD_TARGET = "//apps/sample:app_with_string_resources";
  private static final String AAPT2_BUILD_TARGET =
      "//apps/sample:app_with_string_resources_and_aapt2";

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testExpectedOutputsAreAllAvailable() throws InterruptedException, IOException {
    String buildTarget =
        String.format(
            "%s#%s",
            AAPT1_BUILD_TARGET,
            AndroidBinaryResourcesGraphEnhancer.GENERATE_STRING_RESOURCES_FLAVOR);
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.enableDirCache();
    workspace.runBuckBuild(buildTarget).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    Path output = workspace.buildAndReturnOutput(buildTarget);
    workspace.getBuildLog().assertTargetWasFetchedFromCache(buildTarget);
    verifyOutput(
        output,
        ImmutableSet.of(
            "0000/values/strings.xml", "0001/values/strings.xml", "0002/values/strings.xml"));
  }

  @Test
  public void testExpectedOutputsAreAllAvailableWithAapt2()
      throws InterruptedException, IOException {
    // TODO(dreiss): Remove this when aapt2 is everywhere.
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult foundAapt2 = workspace.runBuckBuild("//apps/sample:check_for_aapt2");
    Assume.assumeTrue(foundAapt2.getExitCode().getCode() == 0);
    String buildTarget =
        String.format(
            "%s#%s",
            AAPT2_BUILD_TARGET,
            AndroidBinaryResourcesGraphEnhancer.GENERATE_STRING_RESOURCES_FLAVOR);
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.enableDirCache();
    Path output = workspace.buildAndReturnOutput(buildTarget);
    verifyOutput(output, ImmutableSet.of("0000/values/strings.xml", "0001/values/strings.xml"));
  }

  private void verifyOutput(Path output, Set<String> expectedFilePaths) throws IOException {
    // verify <output_dir>/<hex_res_dir>/values/strings.xml files
    assertTrue(filesystem.exists(output));
    assertThat(
        filesystem.getFilesUnderPath(filesystem.relativize(output)).stream()
            .map(path -> MorePaths.relativize(filesystem.relativize(output), path).toString())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
        is(expectedFilePaths));
  }
}

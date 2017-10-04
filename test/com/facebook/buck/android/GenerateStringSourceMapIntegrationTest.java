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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class GenerateStringSourceMapIntegrationTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;
  private ProjectFilesystem filesystem;

  private static final String AAPT1_BUILD_TARGET = "//apps/sample:app_with_string_source_map";
  private static final String AAPT2_BUILD_TARGET =
      "//apps/sample:app_with_string_source_map_and_aapt2";

  private static final String STRINGS_XML_PATH_ATTR = "stringsXmlPath";
  private static final String STRINGS_JSON_FILE_NAME = "strings.json";

  @Before
  public void setUp() throws InterruptedException, IOException {
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
            AndroidBinaryResourcesGraphEnhancer.GENERATE_STRING_SOURCE_MAP_FLAVOR);
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.enableDirCache();
    workspace.runBuckBuild(buildTarget).assertSuccess();
    workspace.runBuckCommand("clean").assertSuccess();
    Path output = workspace.buildAndReturnOutput(buildTarget);
    workspace.getBuildLog().assertTargetWasFetchedFromCache(buildTarget);
    verifyOutput(output);
  }

  @Test
  public void testExpectedOutputsAreAllAvailableWithAapt2()
      throws InterruptedException, IOException {
    // TODO(dreiss): Remove this when aapt2 is everywhere.
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectWorkspace.ProcessResult foundAapt2 =
        workspace.runBuckBuild("//apps/sample:check_for_aapt2");
    Assume.assumeTrue(foundAapt2.getExitCode() == 0);
    String buildTarget =
        String.format(
            "%s#%s",
            AAPT2_BUILD_TARGET,
            AndroidBinaryResourcesGraphEnhancer.GENERATE_STRING_SOURCE_MAP_FLAVOR);
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.enableDirCache();
    Path output = workspace.buildAndReturnOutput(buildTarget);
    verifyOutput(output);
  }

  private void verifyOutput(Path output) throws IOException {
    assertTrue(filesystem.exists(output));
    Path stringsJsonFile = output.resolve(STRINGS_JSON_FILE_NAME);
    assertThat(filesystem.exists(stringsJsonFile), is(true));
    String stringsJsonContent = workspace.getFileContents(stringsJsonFile);
    Map<String, Map<String, Map<String, String>>> resourcesInfo =
        new ObjectMapper()
            .readValue(
                stringsJsonContent,
                new TypeReference<Map<String, Map<String, Map<String, String>>>>() {});
    resourcesInfo
        .values()
        .stream()
        .flatMap(resources -> resources.values().stream())
        .forEach(
            resourceInfo -> {
              String stringsXmlPath = resourceInfo.get(STRINGS_XML_PATH_ATTR);
              assertNotNull(stringsXmlPath);
              assertThat(stringsXmlPath.isEmpty(), is(false));
              assertThat(filesystem.exists(workspace.resolve(stringsXmlPath)), is(true));
            });
  }
}

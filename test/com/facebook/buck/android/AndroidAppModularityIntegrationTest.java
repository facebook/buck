/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidAppModularityIntegrationTest extends AbiCompilationModeTest {
  private static final String EOL = System.lineSeparator();

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  public ProjectWorkspace workspace;

  public ProjectFilesystem filesystem;

  private Path testdataDir;

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidAppModularityIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
    testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("app_modularity_integration");
  }

  @Test
  public void testAppModularityMetadata() throws IOException {
    String target = "//apps/multidex:modularity-metadata";
    Path result = workspace.buildAndReturnOutput(target);

    String expected =
        workspace.getFileContents(testdataDir.resolve("testAppModularityMetadata.txt"));

    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
    Path jar =
        workspace
            .getDestPath()
            .resolve(
                "buck-out/gen/java/com/sample/small/lib__small_with_no_resource_deps__output/small_with_no_resource_deps.jar");
    Assert.assertTrue(Files.exists(jar));
  }

  @Test
  public void testAppModularityMetadataNoClasses() throws IOException {
    String target = "//apps/multidex:modularity-metadata-no-classes";
    Path result = workspace.buildAndReturnOutput(target);

    String expected =
        workspace.getFileContents(testdataDir.resolve("testAppModularityMetadataNoClasses.txt"));

    String actual = workspace.getFileContents(result);
    Assert.assertEquals(expected, actual);

    Path jar =
        workspace
            .getDestPath()
            .resolve(
                "buck-out/gen/java/com/sample/small/lib__small_with_no_resource_deps__output/small_with_no_resource_deps.jar");
    Assert.assertFalse(Files.exists(jar));
  }

  @Test
  public void testAppModularityMetadataWithInnerClass() throws IOException {
    String target = "//apps/multidex:modularity-metadata-inner-class";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        workspace.getFileContents(
            testdataDir.resolve("testAppModularityMetadataWithInnerClass.txt"));
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithDeclaredDependency() throws IOException {
    String target = "//apps/multidex:modularity-metadata-simple-declared-dep";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        workspace.getFileContents(
            testdataDir.resolve("testAppModularityMetadataWithDeclaredDependency.txt"));
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithSharedModule() throws IOException {
    String target = "//apps/multidex:modularity-metadata-shared-module";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        workspace.getFileContents(
            testdataDir.resolve("testAppModularityMetadataWithSharedModule.txt"));
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithDecDepsWithSharedTarget() throws IOException {
    String target = "//apps/multidex:modularity-metadata-declared-dep-with-shared-target";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        workspace.getFileContents(
            testdataDir.resolve("testAppModularityMetadataWithDecDepsWithSharedTarget.txt"));
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }
}

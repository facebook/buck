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

import static com.facebook.buck.android.WriteAppModuleMetadataStep.CLASS_SECTION_HEADER;
import static com.facebook.buck.android.WriteAppModuleMetadataStep.DEPS_SECTION_HEADER;
import static com.facebook.buck.android.WriteAppModuleMetadataStep.ITEM_INDENTATION;
import static com.facebook.buck.android.WriteAppModuleMetadataStep.MODULE_INDENTATION;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
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
  }

  @Test
  public void testAppModularityMetadata() throws IOException {
    String target = "//apps/multidex:modularity-metadata";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        CLASS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample2" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample3" + EOL
                + ITEM_INDENTATION + "com/sample/app/MyApplication" + EOL
            + MODULE_INDENTATION + "small_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Small" + EOL
        + DEPS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "small_with_no_resource_deps" + EOL
               + ITEM_INDENTATION + "dex" + EOL;
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithInnerClass() throws IOException {
    String target = "//apps/multidex:modularity-metadata-inner-class";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        CLASS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample2" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample3" + EOL
                + ITEM_INDENTATION + "com/sample/app/MyApplication" + EOL
            + MODULE_INDENTATION + "small_inner_class_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithInnerClass" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithInnerClass$Inner" + EOL
        + DEPS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "small_inner_class_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "dex" + EOL;
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithDeclaredDependency() throws IOException {
    String target = "//apps/multidex:modularity-metadata-simple-declared-dep";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        CLASS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample2" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample3" + EOL
                + ITEM_INDENTATION + "com/sample/app/MyApplication" + EOL
            + MODULE_INDENTATION + "small_inner_class_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithInnerClass" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithInnerClass$Inner" + EOL
            + MODULE_INDENTATION + "small_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Small" + EOL
        + DEPS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "small_inner_class_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "small_with_no_resource_deps" + EOL
               + ITEM_INDENTATION + "dex" + EOL
               + ITEM_INDENTATION + "small_inner_class_with_no_resource_deps" + EOL;
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithSharedModule() throws IOException {
    String target = "//apps/multidex:modularity-metadata-shared-module";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        CLASS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample2" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample3" + EOL
                + ITEM_INDENTATION + "com/sample/app/MyApplication" + EOL
            + MODULE_INDENTATION + "java.com.sample.shared.shared_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Shared" + EOL
            + MODULE_INDENTATION + "small_with_shared2_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithShared2" + EOL
            + MODULE_INDENTATION + "small_with_shared_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithShared" + EOL
        + DEPS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "java.com.sample.shared.shared_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "small_with_shared2_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "java.com.sample.shared.shared_with_no_resource_deps" + EOL
            + MODULE_INDENTATION + "small_with_shared_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "java.com.sample.shared.shared_with_no_resource_deps" + EOL;
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithDecDepsWithSharedTarget() throws IOException {
    String target = "//apps/multidex:modularity-metadata-declared-dep-with-shared-target";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        CLASS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample2" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Sample3" + EOL
                + ITEM_INDENTATION + "com/sample/app/MyApplication" + EOL
            + MODULE_INDENTATION + "small_with_shared2_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/Shared" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithShared2" + EOL
            + MODULE_INDENTATION + "small_with_shared_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "com/facebook/sample/SmallWithShared" + EOL
        + DEPS_SECTION_HEADER + EOL
            + MODULE_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "small_with_shared2_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "dex" + EOL
            + MODULE_INDENTATION + "small_with_shared_with_no_resource_deps" + EOL
                + ITEM_INDENTATION + "dex" + EOL
                + ITEM_INDENTATION + "small_with_shared2_with_no_resource_deps" + EOL;
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }
}

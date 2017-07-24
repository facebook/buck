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

import static com.facebook.buck.android.WriteAppModuleMetadataStep.CLASS_INDENTATION;
import static com.facebook.buck.android.WriteAppModuleMetadataStep.CLASS_SECTION_HEADER;
import static com.facebook.buck.android.WriteAppModuleMetadataStep.MODULE_INDENTATION;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidAppModularityIntegrationTest extends AbiCompilationModeTest {

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
    filesystem = new ProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testAppModularityMetadata() throws IOException {
    String target = "//apps/multidex:modularity-metadata";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        CLASS_SECTION_HEADER
            + System.lineSeparator()
            + MODULE_INDENTATION
            + "dex"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/Sample"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/Sample2"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/Sample3"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/sample/app/MyApplication"
            + System.lineSeparator()
            + MODULE_INDENTATION
            + "small_with_no_resource_deps"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/Small"
            + System.lineSeparator();
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testAppModularityMetadataWithInnerClass() throws IOException {
    String target = "//apps/multidex:modularity-metadata-inner-class";
    Path result = workspace.buildAndReturnOutput(target);
    String expected =
        CLASS_SECTION_HEADER
            + System.lineSeparator()
            + MODULE_INDENTATION
            + "dex"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/Sample"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/Sample2"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/Sample3"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/sample/app/MyApplication"
            + System.lineSeparator()
            + MODULE_INDENTATION
            + "small_inner_class_with_no_resource_deps"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/SmallWithInnerClass"
            + System.lineSeparator()
            + CLASS_INDENTATION
            + "com/facebook/sample/SmallWithInnerClass$Inner"
            + System.lineSeparator();
    String actual = workspace.getFileContents(result);

    Assert.assertEquals(expected, actual);
  }
}

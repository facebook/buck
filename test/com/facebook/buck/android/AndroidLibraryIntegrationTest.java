/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.jvm.kotlin.KotlinTestAssumptions;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidLibraryIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
  }

  @Test
  public void testAndroidLibraryDoesNotUseTransitiveResources()
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult result =
        workspace.runBuckBuild("//java/com/sample/lib:lib_using_transitive_empty_res");
    result.assertFailure();
    assertTrue(result.getStderr().contains("package com.sample does not exist"));
  }

  @Test
  public void testAndroidKotlinBinaryDoesNotUseTransitiveResources()
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
    ProcessResult result =
        workspace.runBuckBuild("//kotlin/com/sample/lib:lib_using_transitive_empty_res");
    result.assertFailure();
    assertTrue(result.getStderr().contains("unresolved reference: R"));
  }

  @Test
  public void testAndroidKotlinLibraryCompilation() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
    ProcessResult result =
        workspace.runBuckBuild("//kotlin/com/sample/lib:lib_depending_on_main_lib");
    result.assertSuccess();
  }

  @Test
  public void testAndroidKotlinLibraryExtraArgumentsCompilation() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
    ProcessResult result =
        workspace.runBuckBuild("//kotlin/com/sample/lib:lib_extra_kotlinc_arguments");
    result.assertSuccess();
  }

  @Test
  public void testAndroidKotlinLibraryMixedSourcesCompilation() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
    ProcessResult result = workspace.runBuckBuild("//kotlin/com/sample/lib:lib_mixed_sources");
    result.assertSuccess();
  }

  @Test(timeout = (3 * 60 * 1000))
  public void testAndroidScalaLibraryDoesNotUseTransitiveResources()
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult result =
        workspace.runBuckBuild("//scala/com/sample/lib:lib_using_transitive_empty_res");
    result.assertFailure();
    assertTrue(result.getStderr().contains("not found: value R"));
  }

  @Test(timeout = (3 * 60 * 1000))
  public void testAndroidScalaLibraryCompilation() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProcessResult result =
        workspace.runBuckBuild("//scala/com/sample/lib:lib_depending_on_main_lib");
    result.assertSuccess();
  }

  @Test(expected = HumanReadableException.class)
  public void testAndroidLibraryBuildFailsWithInvalidLanguageParam()
      throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild("//scala/com/sample/invalid_lang:lib_with_invalid_language_param");
  }
}

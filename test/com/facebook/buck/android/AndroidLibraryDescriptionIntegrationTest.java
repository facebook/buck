/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.junit.Assert.fail;

import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Tests for AndroidLibraryDescription */
public class AndroidLibraryDescriptionIntegrationTest extends AbiCompilationModeTest {
  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_library_dynamic_deps", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
  }

  @Test
  public void testQueryDepsNotInvalidatedWhenRuleKeyHit() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckBuild("//:android_resources").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_resources");

    // Now, build again, assert we get the previous result
    workspace.runBuckBuild("//:android_resources").assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingRuleKey("//:android_resources");

    // Now, edit lib_b, which is NOT part of the query result,
    workspace.replaceFileContents("B.java", "// method", "public static void foo() {}");
    workspace.runBuckBuild("//:android_resources").assertSuccess();

    // And assert that we don't get rebuilt, BUT we had to re-run the query to find out
    workspace.getBuildLog().assertTargetHadMatchingInputRuleKey("//:android_resources");
  }

  @Test
  public void testDepQueryResultsAreInvalidatedWhenDirectDepChanges() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckBuild("//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");

    // Now, edit lib_a, which is NOT part of the query result, and assert the query does not run
    workspace.replaceFileContents("A.java", "// method", "public static void foo() {}");
    workspace.runBuckBuild("//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetHadMatchingRuleKey("//:android_libraries");

    // Now, add lib_a to the 'top_level' library and build again
    workspace.replaceFileContents("BUCK", "#placeholder", "':lib_a',");

    // Have the src for the 'top_level' rule actually use its dependency
    workspace.replaceFileContents("TopLevel.java", "// placeholder", "public A a;");

    // Build again
    workspace.runBuckBuild("//:android_libraries");

    // Now we should rebuild top_level, re-run the query, and rebuild android_libraries
    workspace.getBuildLog().assertTargetBuiltLocally("//:top_level");
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");
  }

  @Test
  public void testDepQueryResultsAreInvalidatedWhenTransitiveDepChanges() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckBuild("//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");

    // Now, edit lib_b, which is part of the query result, and assert the query is invalidated
    workspace.replaceFileContents("B.java", "// method", "public static void foo() {}");
    workspace.runBuckBuild("//:android_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:lib_b");
    workspace.getBuildLog().assertTargetBuiltLocally("//:android_libraries");
  }

  @Test
  public void testDepQueryResultsAreUpdatedWhenAttributesChange() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckBuild("//:has_proc_params").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:has_proc_params");

    // Now, add annotation proc params to lib_b and assert the query is updated
    workspace.replaceFileContents("BUCK", "#annotation_placeholder", "'example.foo=True',");
    workspace.runBuckBuild("//:has_proc_params").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:has_proc_params");
  }

  @Test
  public void testDepQueryResultsCanTakeAdvantageOfDepFileRuleKey() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckBuild("//:java_libraries").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:java_libraries");

    // Now, edit lib_c, which is part of the query result, and assert the query is invalidated
    workspace.replaceFileContents("D.java", "// method", "public static void foo() {}");
    workspace.runBuckBuild("//:java_libraries").assertSuccess();
    // But the libs above get a dep file hit
    workspace.getBuildLog().assertTargetHadMatchingDepfileRuleKey("//:java_libraries");
  }

  @Test
  public void testDepQueryCanApplyToResources() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Build once to warm cache
    workspace.runBuckBuild("//:resources_from_query").assertSuccess();
  }

  @Test
  public void testDepQueryWithClasspathDoesNotTraverseProvidedDeps() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Should succeed because lib_c is a provided dep
    workspace.runBuckBuild("//:provided_only");

    // Should fail becuase 'C.class' is not added to the classpath because it's a provided dep
    workspace.runBuckBuild("//:no_provided_deps").assertFailure();
  }

  @Test
  public void testProvidedDepsQuery() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Should succeed because required dep (lib_c) will be added as provided
    workspace.runBuckBuild("//:has_lib_c_from_provided_query").assertSuccess();
  }

  @Test
  public void testProvidedDepsQueryDoesNotAffectPackaging() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild("//:check_output_of_does_not_package_lib_c").assertSuccess();
    String[] outputs =
        workspace
            .getFileContents(getOutputFile("//:check_output_of_does_not_package_lib_c"))
            .split("\\s");
    // There should be a class entry for UsesC.java
    Assert.assertThat(outputs, Matchers.hasItemInArray("com/facebook/example/UsesC.class"));
    // But not one for C.java
    Assert.assertThat(
        outputs, Matchers.not(Matchers.hasItemInArray("com/facebook/example/C.class")));
  }

  @Test
  public void testClasspathQueryCanTraverseAndroidResource() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    workspace.runBuckBuild("//:needs_b_has_res").assertSuccess();
  }

  @Test
  public void testClasspathQueryOnAndroidResourceRespectsDepth() throws Exception {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    // Check that we have b in our resolved deps, but not c, due to the depth limiting
    workspace.runBuckBuild("//:needs_c_has_res").assertFailure();
  }

  private Path getOutputFile(String targetName) {
    try {
      ProcessResult buildResult =
          workspace.runBuckCommand("targets", targetName, "--show-output", "--json");
      buildResult.assertSuccess();
      JsonNode jsonNode = ObjectMappers.READER.readTree(buildResult.getStdout()).get(0);
      assert jsonNode.has("buck.outputPath");
      return Paths.get(jsonNode.get("buck.outputPath").asText());
    } catch (Exception e) {
      fail(e.getMessage());
      return Paths.get("");
    }
  }
}

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

package com.facebook.buck.js;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsRulesIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectWorkspace workspace;
  private ProjectFilesystem projectFilesystem;
  private Path genPath;

  @Before
  public void setUp() throws InterruptedException, IOException {
    // worker tool does not work on windows
    assumeFalse(Platform.detect() == Platform.WINDOWS);
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "js_rules", tmp);
    workspace.setUp();
    projectFilesystem = new ProjectFilesystem(workspace.getDestPath());
    genPath = projectFilesystem.getBuckPaths().getGenDir();
  }

  @Test
  public void testSimpleLibraryBuild() throws IOException {
    workspace.runBuckBuild("//js:fruit").assertSuccess();

    workspace.verify(Paths.get("simple_library_build.expected"), genPath);
  }

  @Test
  public void testBuildWithExtraArgs() throws IOException {
    workspace.runBuckBuild("//js:extras").assertSuccess();

    workspace.verify(Paths.get("with_extra_args.expected"), genPath);
  }

  @Test
  public void testOptimizationBuild() throws IOException {
    workspace.runBuckBuild("//js:fruit#release,android").assertSuccess();

    workspace.verify(Paths.get("simple_release_build.expected"), genPath);
  }

  @Test
  public void testBuildWithDeps() throws IOException {
    workspace.runBuckBuild("//js:fruit-salad").assertSuccess();

    workspace.verify(Paths.get("with_deps.expected"), genPath);
  }

  @Test
  public void testReleaseBuildWithDeps() throws IOException {
    workspace.runBuckBuild("//js:fruit-salad#release,ios").assertSuccess();

    workspace.verify(Paths.get("release_flavor_with_deps.expected"), genPath);
  }

  @Test
  public void testFlavoredAndUnflavoredBuild() throws IOException {
    workspace.runBuckBuild("//js:fruit#release,android", "//js:fruit").assertSuccess();

    workspace.verify(Paths.get("same_target_with_and_without_flavors.expected"), genPath);
  }

  @Test
  public void testBuildTargetOutputs() throws IOException {
    workspace.runBuckBuild("//js:build-target-output").assertSuccess();

    workspace.verify(Paths.get("with_build_target.expected"), genPath);
  }

  @Test
  public void testReplacePrefixes() throws IOException {
    workspace
        .runBuckBuild("//external:replace-file-prefix", "//js:replace-build-target-prefix")
        .assertSuccess();

    workspace.verify(Paths.get("replace_path_prefix.expected"), genPath);
  }

  @Test
  public void testSubPathsOfBuildTargets() throws IOException {
    workspace.runBuckBuild("//js:node_modules").assertSuccess();

    workspace.verify(Paths.get("subpaths.expected"), genPath);
  }

  @Test
  public void testBundleBuild() throws IOException {
    workspace.runBuckBuild("//js:fruit-salad-in-a-bundle#ios").assertSuccess();

    workspace.verify(Paths.get("simple_bundle.expected"), genPath);
  }

  @Test
  public void testBundleBuildWithFlavors() throws IOException {
    workspace.runBuckBuild("//js:fruit-salad-in-a-bundle#android,release").assertSuccess();

    workspace.verify(Paths.get("simple_bundle_with_flavors.expected"), genPath);
  }

  @Test
  public void testBundleBuildWithName() throws IOException {
    workspace.runBuckBuild("//js:fruit-with-extras").assertSuccess();

    workspace.verify(Paths.get("named_flavored_bundle.expected"), genPath);
  }

  @Test
  public void androidApplicationsContainsJsAndResources() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    BuildTarget target = BuildTargetFactory.newInstance("//android/apps/sample:app");
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(BuildTargets.getGenPath(projectFilesystem, target, "%s.apk")));

    zipInspector.assertFileExists("assets/fruit-salad-in-a-bundle.js");
    zipInspector.assertFileExists("res/drawable-mdpi-v4/pixel.gif");
  }

  @Test
  public void bundleWithAndroidLibraryDependency() throws IOException {
    workspace.runBuckBuild("//js:bundle-with-android-lib#android,release").assertSuccess();
    workspace.verify(Paths.get("android_library_bundle.expected"), genPath);
  }

  @Test
  public void iOSApplicationContainsJsAndResources() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    workspace.runBuckBuild("//ios:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();
    workspace.verify(Paths.get("ios_app.expected"), genPath);
  }
}

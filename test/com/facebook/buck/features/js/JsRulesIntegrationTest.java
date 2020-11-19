/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.features.js;

import static org.hamcrest.Matchers.everyItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.impl.TargetConfigurationHasher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.PredicateMatcher;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class JsRulesIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  private ProjectWorkspace workspace;
  private ProjectFilesystem projectFilesystem;
  private RelPath genPath;

  @Before
  public void setUp() throws IOException {
    // worker tool does not work on windows
    assumeFalse(Platform.detect() == Platform.WINDOWS);
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "js_rules", tmp);
    workspace.setUp();
    projectFilesystem = workspace.getProjectFileSystem();
    genPath = projectFilesystem.getBuckPaths().getGenDir();
    if (projectFilesystem.getBuckPaths().shouldIncludeTargetConfigHash()) {
      genPath =
          genPath.resolveRel(
              TargetConfigurationHasher.hash(UnconfiguredTargetConfiguration.INSTANCE));
    }
  }

  private static ObjectMapper getPrettyPrintingObjectMapper() {
    DefaultPrettyPrinter.Indenter indenter =
        new DefaultIndenter(
            "  ",
            // Use platform-invariant newlines.
            "\n");
    return new ObjectMapper()
        .setDefaultPrettyPrinter(
            new DefaultPrettyPrinter().withArrayIndenter(indenter).withObjectIndenter(indenter))
        .enable(SerializationFeature.INDENT_OUTPUT);
  }

  private static String normalizeObservedContent(String content) {
    ObjectMapper mapper = getPrettyPrintingObjectMapper();

    // Replace a string like `fruit#file-apple.js-9635e8d52e.jsfile`
    // with `fruit#file-apple.js-<HASH>.jsfile`.
    return Arrays.stream(
            content
                .replaceAll(
                    "\\.(js|json)-[0-9a-f]{10}((?:,ios|,release|,transform-profile-default)*)\\.jsfile",
                    ".$1-<HASH>$2.jsfile")
                .split("\n"))
        .map(
            line -> {
              // Pretty-print embedded JSON objects.
              if (line.startsWith("{") && line.endsWith("}")) {
                try {
                  Object jsonObject = mapper.readValue(line, Object.class);
                  return mapper.writer().writeValueAsString(jsonObject);
                } catch (IOException e) {
                  return line;
                }
              } else {
                return line;
              }
            })
        .collect(Collectors.joining("\n"));
  }

  @Test
  public void testSimpleLibraryBuild() throws IOException {
    workspace.runBuckBuild("//js:fruit#transform-profile-default").assertSuccess();

    workspace.verify(
        Paths.get("simple_library_build.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void librariesDoNotMaterializeGeneratedDeps() throws IOException {
    String libraryTarget = "//js:lib-depending-on-lib-with-generated-sources";

    workspace.enableDirCache();
    workspace.runBuckBuild(libraryTarget).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");

    // changing this file invalidates the library target, but not the library dependency with
    // generated source files. Thus, the generated sources should not be materialized in buck-out,
    // which we just cleaned with the preceding command.
    assertTrue(workspace.replaceFileContents("js/apple.js", "apple", "apple=\"braeburn\""));
    workspace.runBuckBuild("--shallow", libraryTarget).assertSuccess();

    String[] bits =
        workspace
            .runBuckCommand("targets", "--show-full-output", "//external:node-modules-installation")
            .assertSuccess()
            .getStdout()
            .split("\\s+");
    File generated = Paths.get(bits[1]).toFile();
    assertFalse(generated.exists());
  }

  @Test
  public void bundlesMaterializeGeneratedDeps() throws IOException {
    String bundleTarget = "//js:bundle-with-generated-sources";

    // We build all dependencies of the bundle target, and clean buck-out/ afterwards. That means
    // that buck can reuse cached artifacts on the next run, where we will build the bundle
    // target itself.
    workspace.enableDirCache();
    Stream<String> directDeps =
        Arrays.stream(
                workspace
                    .runBuckCommand("query", String.format("deps(%s, 1)", bundleTarget))
                    .assertSuccess()
                    .getStdout()
                    .split("\\s+"))
            .filter(s -> !s.equals(bundleTarget));
    workspace.runBuckBuild(directDeps.toArray(String[]::new)).assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");

    workspace.runBuckBuild("--shallow", bundleTarget).assertSuccess();

    String[] bits =
        workspace
            .runBuckCommand(
                "targets",
                "--show-full-output",
                "//external:node-modules-installation",
                "//external:exported.js")
            .assertSuccess()
            .getStdout()
            .split("\\s+");

    ImmutableList<File> generatedSources =
        Stream.of(bits[1], bits[3])
            .map(Paths::get)
            .map(Path::toFile)
            .collect(ImmutableList.toImmutableList());
    assertThat(generatedSources, everyItem(new PredicateMatcher<>("path exists", File::exists)));
  }

  @Test
  public void testLibraryWithExtraJson() throws IOException {
    workspace.runBuckBuild("//js:extras#transform-profile-default").assertSuccess();

    workspace.verify(
        Paths.get("with_extra_json.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testBuildWithExtraJson() throws IOException {
    workspace.runBuckBuild("//js:bundle_with_extra_json").assertSuccess();

    workspace.verify(
        Paths.get("bundle_with_extra_json.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testOptimizationBuild() throws IOException {
    workspace.runBuckBuild("//js:fruit#release,android,transform-profile-default").assertSuccess();

    workspace.verify(
        Paths.get("simple_release_build.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testBuildWithDeps() throws IOException {
    workspace.runBuckBuild("//js:fruit-salad#transform-profile-default").assertSuccess();

    workspace.verify(
        Paths.get("with_deps.expected"), genPath, JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testReleaseBuildWithDeps() throws IOException {
    workspace
        .runBuckBuild("//js:fruit-salad#release,ios,transform-profile-default")
        .assertSuccess();

    workspace.verify(
        Paths.get("release_flavor_with_deps.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testFlavoredAndUnflavoredBuild() throws IOException {
    workspace
        .runBuckBuild(
            "//js:fruit#release,android,transform-profile-default",
            "//js:fruit#transform-profile-default")
        .assertSuccess();

    workspace.verify(
        Paths.get("same_target_with_and_without_flavors.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testBuildTargetOutputs() throws IOException {
    workspace.runBuckBuild("//js:build-target-output#transform-profile-default").assertSuccess();

    workspace.verify(
        Paths.get("with_build_target.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testReplacePrefixes() throws IOException {
    workspace
        .runBuckBuild(
            "//external:replace-file-prefix#transform-profile-default",
            "//js:replace-build-target-prefix#transform-profile-default")
        .assertSuccess();

    workspace.verify(
        Paths.get("replace_path_prefix.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testSubPathsOfBuildTargets() throws IOException {
    workspace.runBuckBuild("//js:node_modules#transform-profile-default").assertSuccess();

    workspace.verify(
        Paths.get("subpaths.expected"), genPath, JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testLibraryWithDepsQuery() throws IOException {
    workspace.runBuckBuild("//js:lib-with-deps-query#transform-profile-default").assertSuccess();

    workspace.verify(
        Paths.get("with_deps_query.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testBundleBuild() throws IOException {
    workspace.runBuckBuild("//js:fruit-salad-in-a-bundle#ios").assertSuccess();

    workspace.verify(
        Paths.get("simple_bundle.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testBundleBuildWithFlavors() throws IOException {
    workspace.runBuckBuild("//js:fruit-salad-in-a-bundle#android,release").assertSuccess();

    workspace.verify(
        Paths.get("simple_bundle_with_flavors.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void testBundleBuildWithName() throws IOException {
    workspace.runBuckBuild("//js:fruit-with-extras").assertSuccess();

    workspace.verify(
        Paths.get("named_flavored_bundle.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void androidApplicationsContainsJsAndResources() throws IOException {
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();

    BuildTarget target = BuildTargetFactory.newInstance("//android/apps/sample:app");
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), target, "%s.apk")));

    zipInspector.assertFileExists("assets/fruit-salad-in-a-bundle.js");
    zipInspector.assertFileExists("res/drawable-mdpi-v4/pixel.gif");
  }

  @Test
  public void bundleWithAndroidLibraryDependency() throws IOException {
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();

    workspace.runBuckBuild("//js:bundle-with-android-lib#android,release").assertSuccess();
    workspace.verify(
        Paths.get("android_library_bundle.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void iOSApplicationContainsJsAndResources() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    workspace.runBuckBuild("//ios:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();
    workspace.verify(
        Paths.get("ios_app.expected"), genPath, JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void generatesProjectWithJsBundleDependency() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    workspace
        .runBuckCommand(
            "project",
            "--config",
            "project.ide=xcode",
            "//ios:DemoApp#iphonesimulator-x86_64,no-debug")
        .assertSuccess();
    workspace.verify(
        Paths.get("ios_project.expected"),
        workspace.getDestPath(),
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void dependencyFile() throws IOException {
    workspace
        .runBuckBuild(
            "//js:fruit-salad-in-a-bundle#dependencies,ios,release",
            "//js:fruit-with-extras#android,dependencies")
        .assertSuccess();
    workspace.verify(
        Paths.get("dependencies.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void sourcemapCanBeAccessedWithoutDependingOnBundle() {
    workspace.runBuckBuild("//js:genrule-using-only-sourcemap").assertSuccess();
  }

  @Test
  public void bundleGenrule() throws IOException {
    workspace.runBuckBuild("//js:genrule-inner", "//js:genrule-outer").assertSuccess();
    workspace.verify(
        Paths.get("bundle_genrules.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);

    String genruleSourceMapTarget = "//js:genrule-outer#source_map";
    String underlyingBundleSourceMapTarget = "//js:fruit-with-extras#source_map";
    ImmutableMap<String, Path> sourceMapPaths =
        workspace.buildMultipleAndReturnOutputs(
            genruleSourceMapTarget, underlyingBundleSourceMapTarget);
    assertEquals(
        sourceMapPaths.get(underlyingBundleSourceMapTarget),
        sourceMapPaths.get(genruleSourceMapTarget));

    String genruleDepsTarget = "//js:genrule-outer#dependencies";
    String underlyingBundleDepsTarget = "//js:fruit-with-extras#dependencies";
    ImmutableMap<String, Path> depsPaths =
        workspace.buildMultipleAndReturnOutputs(genruleDepsTarget, underlyingBundleDepsTarget);
    assertEquals(depsPaths.get(underlyingBundleDepsTarget), depsPaths.get(genruleDepsTarget));
  }

  @Test
  public void appleBundleDependingOnJsBundleGenruleContainsBundleAndResources() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    workspace
        .runBuckBuild("//ios:DemoAppWithJsBundleGenrule#iphonesimulator-x86_64,no-debug")
        .assertSuccess();
    workspace.verify(
        Paths.get("ios_app_with_genrule.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void generatesProjectWithJsBundleGenruleDependency() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.MACOSX));

    workspace
        .runBuckCommand(
            "project",
            "--config",
            "project.ide=xcode",
            "//ios:DemoAppWithJsBundleGenrule#iphonesimulator-x86_64,no-debug")
        .assertSuccess();
    workspace.verify(
        Paths.get("ios_project_with_genrule.expected"),
        workspace.getDestPath(),
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void apkContainsGenruleOutputAndBundleResources() throws IOException {
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();

    BuildTarget target = BuildTargetFactory.newInstance("//android/apps/sample:app_with_genrule");
    workspace.runBuckBuild(target.getFullyQualifiedName()).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), target, "%s.apk")));

    zipInspector.assertFileExists("assets/postprocessed.txt");
    zipInspector.assertFileExists("res/drawable-mdpi-v4/pixel.gif");
    zipInspector.assertFileDoesNotExist("assets/fruit-salad-in-a-bundle.js");
  }

  @Test
  public void genruleAllowsToRewriteSourcemap() throws IOException {
    workspace.runBuckBuild("//js:sourcemap-genrule#source_map").assertSuccess();
    workspace.verify(
        Paths.get("sourcemap_genrule.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void genruleSourcemapCanBeAccessedWithoutDependingOnBundle() {
    workspace.runBuckBuild("//js:genrule-using-only-sourcemap-of-bundle-genrule").assertSuccess();
  }

  @Test
  public void genruleAllowsToRewriteMiscDir() throws IOException {
    workspace.runBuckBuild("//js:misc-genrule").assertSuccess();
    workspace.verify(
        Paths.get("misc_genrule.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }

  @Test
  public void genruleAllowsToRewriteDepsFile() throws IOException {
    workspace.runBuckBuild("//js:deps-file-genrule#dependencies").assertSuccess();
    workspace.verify(
        Paths.get("deps_file_genrule.expected"),
        genPath,
        JsRulesIntegrationTest::normalizeObservedContent);
  }
}

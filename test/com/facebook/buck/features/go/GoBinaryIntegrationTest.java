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

package com.facebook.buck.features.go;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GoBinaryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void ensureGoIsAvailable() {
    GoAssumptions.assumeGoCompilerAvailable();
  }

  @Test
  public void simpleBinary() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//:xyzzy");
    workspace.resetBuildLogFile();

    ProcessExecutor.Result result =
        workspace.runCommand(
            workspace
                .getGenPath(BuildTargetFactory.newInstance("//:xyzzy"), "%s")
                .resolve("xyzzy")
                .toString());
    assertThat(result.getExitCode(), Matchers.equalTo(0));
    assertThat(result.getStdout().get(), Matchers.containsString("Hello, world!"));
    assertThat(result.getStderr().get(), Matchers.blankString());
  }

  @Test
  public void binaryWithAsm() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "asm", tmp);
    workspace.setUp();

    // gensymabis was introduced in go 1.12
    List<Integer> versionNumbers = GoAssumptions.getActualVersionNumbers();
    if (versionNumbers.get(1) >= 12 && versionNumbers.get(0) >= 1) {
      workspace.addBuckConfigLocalOption("go", "gensymabis", "true");
    }

    ProcessResult result = workspace.runBuckCommand("run", "//src/asm_test:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("Sum is 6"));
  }

  @Test
  public void binaryWithAsmAndArchBuildTag() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "asm_with_arch_tag", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/asm_test:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("Sum is 6"));
  }

  @Test
  public void binaryWithCgo() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/simple:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("fmt: Go string"));
  }

  @Test
  public void binaryWithCgoAndGenruleAsSource() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/genrule:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("fmt: Go string second"));
  }

  @Test
  public void binaryWithLibraryIncludingCgoLib() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("run", "//src/interdeps:bin");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("fmt: Go string"));
  }

  @Test
  public void binaryWithExportedGoFunctionsToCxx() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    // Test C++ version (C version with extern "C" compiled in CXX mode)
    ProcessResult result = workspace.runBuckCommand("run", "//src/c_calls_go:bin_cpp");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("From CPP: 3"));

    // Test C version
    result = workspace.runBuckCommand("run", "//src/c_calls_go:bin_c");
    result.assertSuccess();
    assertThat(result.getStdout(), Matchers.containsString("From C: 3"));
  }

  @Test
  public void buildAfterChangeWorks() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "simple_binary", tmp);
    workspace.setUp();

    workspace.runBuckBuild("//:xyzzy").assertSuccess();
    workspace.writeContentsToPath(
        workspace.getFileContents("main.go") + "// this is a comment", "main.go");
    workspace.runBuckBuild("//:xyzzy").assertSuccess();
  }

  @Test
  public void binaryWithLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void binaryWithResources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_resources", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void vendoredLibrary() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "vendored_library", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void libraryWithPrefix() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "library_with_prefix", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
  }

  @Test
  public void libraryWithPrefixAfterChange() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "library_with_prefix", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world!"));
    workspace.writeContentsToPath(
        workspace.getFileContents("messenger/printer/printer.go").replace('!', '?'),
        "messenger/printer/printer.go");
    assertThat(
        workspace.runBuckCommand("run", "//:hello").assertSuccess().getStdout(),
        Matchers.containsString("Hello, world?"));
  }

  @Test
  public void nonGoLibraryDepErrors() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_library", tmp);
    workspace.setUp();

    ProcessResult processResult = workspace.runBuckCommand("run", "//:illegal_dep");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(), Matchers.containsString("is not an instance of go_library"));
  }

  @Test
  public void exportedDeps() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "exported_deps", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void generatedSources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "generated_source", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void generatedSourceDir() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "generated_source_dir", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void emptySources() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "empty_sources", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:main").assertSuccess();
  }

  @Test
  public void rebuildingBinaryFromCacheWorksWithTransitiveDep() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "transitive_dep", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    // Do an initial built to warm the cache.
    workspace.runBuckBuild("//:main").assertSuccess();

    // Clean the build products, as we're going to test that pulling from cache works.
    workspace.runBuckCommand("clean", "--keep-cache");

    // Make a white-space only change -- enough to force a relink of the binary.
    workspace.replaceFileContents("main.go", "a.A()", " a.A()");

    // Run another build and verify it successfully built locally.
    workspace.runBuckBuild("//:main").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:main");
  }

  @Test
  public void buildConstraints() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "build_constraints", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:family").assertSuccess();
  }

  @Test
  public void cgoIncludeHeaderFromSamePackage() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", "//src/mixed_with_c:bin");
    result.assertSuccess();
  }

  @Test
  public void cgoSharedBinaryLinkStyle() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", "//src/mixed_with_c:bin-shared");
    result.assertSuccess();

    Path output =
        workspace.getScratchPath(
            BuildTargetFactory.newInstance("//src/mixed_with_c:bin-shared"), "%s.argsfile");

    assertTrue(output.toFile().exists());
    assertThat(
        Files.readAllLines(output), hasItem(Matchers.containsString("libsrc_mixed_with_c_lib.so")));
  }

  @Test
  public void generatedCgoPackage() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", "//src/gen_pkg:bin");
    result.assertSuccess();
  }

  @Test
  public void cgoLibraryWithGoNativeDeps() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    ProcessResult result = workspace.runBuckCommand("run", "//src/cgo_with_go_deps:bin");
    result.assertSuccess();
  }

  @Test
  public void cgoLibraryWithDifferentPackageName() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    workspace.runBuckCommand("run", "//src/different_package/cli:cli").assertSuccess();
  }

  @Test
  public void cgoLibraryWithCxxPrebuiltDep() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand("run", "//src/prebuilt_cxx_lib/cli:cli")
            .assertSuccess()
            .getStdout(),
        Matchers.containsString("called remote_function"));
  }

  @Test
  public void cgoLibraryWithCxxPrebuiltDepBuiltWithWrappedCpp() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();

    assertThat(
        workspace
            .runBuckCommand(
                "run",
                "-c",
                "cxx.cpp=//cpp_wrapper:cpp_wrapper",
                "-c",
                "cxx.cpp_type=gcc",
                "//src/prebuilt_cxx_lib/cli:cli")
            .assertSuccess()
            .getStdout(),
        Matchers.containsString("called remote_function"));
  }

  @Test
  public void binaryWithPrebuilt() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:bin").assertSuccess().getStdout(),
        Matchers.containsString("foo"));
  }

  @Test
  public void libraryWithPrebuilt() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "library_with_prebuilt", tmp);
    workspace.setUp();

    assertThat(
        workspace.runBuckCommand("run", "//:bin").assertSuccess().getStdout(),
        Matchers.containsString("foo"));
  }

  @Test
  public void binaryWithSharedCgoDepsCanBeRebuiltFromCache() throws IOException {
    GoAssumptions.assumeGoVersionAtLeast("1.10.0");
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    workspace.enableDirCache();

    // Do an initial run to warm the cache and verify shared linking is working
    workspace.runBuckCommand("run", "//src/mixed_with_c:bin-shared").assertSuccess();

    // Clean the build products, as we're going to test that pulling from cache works.
    workspace.runBuckCommand("clean", "--keep-cache");

    // Run another run to ensure that shared libs were fetched from cache
    workspace.runBuckCommand("run", "//src/mixed_with_c:bin-shared").assertSuccess();

    workspace.getBuildLog().assertTargetWasFetchedFromCache("//src/mixed_with_c:bin-shared");
  }

  @Test
  public void testCgoBinaryWithCxxThinArchives() throws IOException {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    assumeTrue(
        cxxPlatform
            .getAr()
            .resolve(ruleResolver, UnconfiguredTargetConfiguration.INSTANCE)
            .supportsThinArchives());
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "cgo", tmp);
    workspace.setUp();
    workspace.enableDirCache();
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//src/cgo_with_go_deps:bin")
        .assertSuccess();
    // we are looking for the .o files produced by cxx library /src/cxx (not cgo toolchain)
    ImmutableSortedSet<Path> initialObjects =
        findFiles(tmp.getRoot(), tmp.getRoot().getFileSystem().getPathMatcher("glob:**/cxx**/*.o"));
    workspace.runBuckCommand("clean", "--keep-cache");
    workspace
        .runBuckBuild(
            "-c",
            "cxx.cache_links=false",
            "-c",
            "cxx.archive_contents=thin",
            "//src/cgo_with_go_deps:bin")
        .assertSuccess();
    ImmutableSortedSet<Path> subsequentObjects =
        findFiles(tmp.getRoot(), tmp.getRoot().getFileSystem().getPathMatcher("glob:**/cxx**/*.o"));
    assertThat(initialObjects, Matchers.equalTo(subsequentObjects));
    assertTrue(initialObjects.size() > 0);
  }

  /**
   * Test that Go binaries work with custom C++ linker wrapper tools which includes extra arguments
   * (which would otherwise require bash quoting).
   */
  @Test
  public void binaryWithCustomCxxLdTool() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "binary_with_custom_ld_tool", tmp);
    workspace.setUp();
    workspace.runBuckBuild("//:bin").assertSuccess();
  }

  private ImmutableSortedSet<Path> findFiles(Path root, PathMatcher matcher) throws IOException {
    ImmutableSortedSet.Builder<Path> files = ImmutableSortedSet.naturalOrder();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (matcher.matches(file)) {
              files.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return files.build();
  }
}

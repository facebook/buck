/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.python;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.features.python.PythonBuckConfig.PackageStyle;
import com.facebook.buck.features.python.toolchain.impl.PythonInterpreterFromConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutor.Result;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.unarchive.Unzip;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class PythonBinaryIntegrationTest {

  @Parameterized.Parameters(name = "{0}(dir={1}),{2}")
  public static Collection<Object[]> data() {
    ImmutableList.Builder<Object[]> validPermutations = ImmutableList.builder();
    for (PythonBuckConfig.PackageStyle packageStyle : PythonBuckConfig.PackageStyle.values()) {
      for (boolean pexDirectory : new boolean[] {true, false}) {
        if (packageStyle == PythonBuckConfig.PackageStyle.INPLACE && pexDirectory) {
          continue;
        }

        for (NativeLinkStrategy linkStrategy : NativeLinkStrategy.values()) {
          validPermutations.add(new Object[] {packageStyle, pexDirectory, linkStrategy});
        }
      }
    }
    return validPermutations.build();
  }

  @Parameterized.Parameter public PythonBuckConfig.PackageStyle packageStyle;

  @Parameterized.Parameter(value = 1)
  public boolean pexDirectory;

  @Parameterized.Parameter(value = 2)
  public NativeLinkStrategy nativeLinkStrategy;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  public ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "python_binary", tmp);
    workspace.setUp();
    String pexFlags = pexDirectory ? "--directory" : "";
    workspace.writeContentsToPath(
        "[python]\n"
            + "  package_style = "
            + packageStyle.toString().toLowerCase()
            + "\n"
            + "  native_link_strategy = "
            + nativeLinkStrategy.toString().toLowerCase()
            + "\n"
            + "  pex_flags = "
            + pexFlags,
        ".buckconfig");
    PythonBuckConfig config = getPythonBuckConfig();
    assertThat(config.getPackageStyle(), equalTo(packageStyle));
    assertThat(config.getNativeLinkStrategy(), equalTo(nativeLinkStrategy));
  }

  @Test
  public void nonComponentDepsArePreserved() throws IOException {
    workspace.runBuckBuild("//:bin-with-extra-dep").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:extra");
  }

  @Test
  public void executionThroughSymlink() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), Matchers.oneOf(Platform.MACOS, Platform.LINUX));
    workspace.runBuckBuild("//:bin").assertSuccess();
    String output =
        workspace
            .runBuckCommand("targets", "--show-output", "//:bin")
            .assertSuccess()
            .getStdout()
            .trim();
    Path link = workspace.getPath("link");
    CreateSymlinksForTests.createSymLink(
        link, workspace.getPath(Splitter.on(" ").splitToList(output).get(1)).toAbsolutePath());

    ProcessExecutor.Result result =
        workspace.runCommand(
            new PythonInterpreterFromConfig(getPythonBuckConfig(), new ExecutableFinder())
                .getPythonInterpreterPath()
                .toString(),
            link.toString());
    assertThat(
        result.getStdout().orElse("") + result.getStderr().orElse(""),
        result.getExitCode(),
        equalTo(0));
  }

  @Test
  public void commandLineArgs() throws IOException {
    ProcessResult result = workspace.runBuckCommand("run", ":bin", "HELLO WORLD").assertSuccess();
    assertThat(result.getStdout(), containsString("HELLO WORLD"));
  }

  @Test
  public void testOutput() throws IOException {
    workspace.runBuckBuild("//:bin").assertSuccess();

    File output = workspace.getPath("buck-out/gen/bin.pex").toFile();
    if (pexDirectory) {
      assertTrue(output.isDirectory());
    } else {
      assertTrue(output.isFile());
    }
  }

  @Test
  public void nativeLibraries() throws IOException {
    assumeThat(packageStyle, equalTo(PythonBuckConfig.PackageStyle.INPLACE));
    assumeThat(
        "TODO(8667197): Native libs currently don't work on El Capitan",
        Platform.detect(),
        not(equalTo(Platform.MACOS)));
    ProcessResult result = workspace.runBuckCommand("run", ":bin-with-native-libs").assertSuccess();
    assertThat(result.getStdout(), containsString("HELLO WORLD"));
  }

  @Test
  public void runFromGenrule() throws IOException {
    workspace.runBuckBuild(":gen").assertSuccess();
  }

  @Test
  public void arg0IsPreserved() throws IOException {
    workspace.writeContentsToPath("import sys; print(sys.argv[0])", "main.py");
    String arg0 = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    String output =
        workspace
            .runBuckCommand("targets", "--show-output", "//:bin")
            .assertSuccess()
            .getStdout()
            .trim();
    assertThat(arg0, endsWith(Splitter.on(" ").splitToList(output).get(1)));
  }

  @Test
  public void nativeLibsEnvVarIsPreserved() throws IOException {
    BuildRuleResolver resolver = new TestActionGraphBuilder();

    assumeThat(
        "TODO(8667197): Native libs currently don't work on El Capitan",
        Platform.detect(),
        not(equalTo(Platform.MACOS)));

    String nativeLibsEnvVarName =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()))
            .getLd()
            .resolve(resolver)
            .searchPathEnvVar();
    String originalNativeLibsEnvVar = "something";
    workspace.writeContentsToPath(
        String.format("import os; print(os.environ.get('%s'))", nativeLibsEnvVarName),
        "main_with_native_libs.py");

    // Pre-set library path.
    String nativeLibsEnvVar =
        workspace
            .runBuckCommandWithEnvironmentOverridesAndContext(
                workspace.getPath(""),
                Optional.empty(),
                ImmutableMap.of(nativeLibsEnvVarName, originalNativeLibsEnvVar),
                "run",
                ":bin-with-native-libs")
            .assertSuccess()
            .getStdout()
            .trim();
    assertThat(nativeLibsEnvVar, equalTo(originalNativeLibsEnvVar));

    // Empty library path.
    nativeLibsEnvVar =
        workspace
            .runBuckCommandWithEnvironmentOverridesAndContext(
                workspace.getPath(""),
                Optional.empty(),
                ImmutableMap.of(),
                "run",
                ":bin-with-native-libs")
            .assertSuccess()
            .getStdout()
            .trim();
    assertThat(nativeLibsEnvVar, equalTo("None"));
  }

  @Test
  public void sysPathDoesNotIncludeWorkingDir() throws IOException {
    workspace.writeContentsToPath("import sys; print(sys.path[0])", "main.py");
    String sysPath0 = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    assertThat(sysPath0, not(equalTo("")));
  }

  @Test
  public void binaryIsCachedProperly() throws IOException {
    // Verify that the flow of build, upload to cache, clean, then re-build (and potentially
    // fetching from cache) results in a usable binary.
    workspace.writeContentsToPath("print('hello world')", "main.py");
    workspace.enableDirCache();
    workspace.runBuckBuild(":bin").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    String stdout = workspace.runBuckCommand("run", ":bin").assertSuccess().getStdout().trim();
    assertThat(stdout, equalTo("hello world"));
  }

  @Test
  public void externalPexToolAffectsRuleKey() throws IOException {
    assumeThat(packageStyle, equalTo(PythonBuckConfig.PackageStyle.STANDALONE));

    ProcessResult firstResult =
        workspace.runBuckCommand(
            "targets", "-c", "python.path_to_pex=//:pex_tool", "--show-rulekey", "//:bin");
    String firstRuleKey = firstResult.assertSuccess().getStdout().trim();

    workspace.writeContentsToPath("changes", "pex_tool.sh");

    ProcessResult secondResult =
        workspace.runBuckCommand(
            "targets", "-c", "python.path_to_pex=//:pex_tool", "--show-rulekey", "//:bin");
    String secondRuleKey = secondResult.assertSuccess().getStdout().trim();

    assertThat(secondRuleKey, not(equalTo(firstRuleKey)));
  }

  @Test
  public void multiplePythonHomes() throws Exception {
    assumeThat(Platform.detect(), not(Matchers.is(Platform.WINDOWS)));
    ProcessResult result =
        workspace.runBuckBuild(
            "-c",
            "python#a.library=//:platform_a",
            "-c",
            "python#b.library=//:platform_b",
            "//:binary_with_extension_a",
            "//:binary_with_extension_b");
    result.assertSuccess();
  }

  @Test
  public void mainModuleNameIsSetProperly() throws Exception {
    assumeThat(packageStyle, not(Matchers.is(PythonBuckConfig.PackageStyle.STANDALONE)));
    workspace.runBuckCommand("run", "//:main_module_bin").assertSuccess();
  }

  @Test
  public void disableCachingForPackagedBinaries() throws IOException {
    assumeThat(packageStyle, Matchers.is(PythonBuckConfig.PackageStyle.STANDALONE));
    workspace.enableDirCache();
    workspace.runBuckBuild("-c", "python.cache_binaries=false", ":bin").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace.runBuckBuild("-c", "python.cache_binaries=false", ":bin").assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:bin");
  }

  @Test
  public void standalonePackagePrebuiltLibrariesProperly()
      throws IOException, InterruptedException {
    assumeThat(packageStyle, Matchers.is(PythonBuckConfig.PackageStyle.STANDALONE));

    workspace.runBuckCommand("run", "//:main_module_with_prebuilt_dep_bin").assertSuccess();
    Path binPath =
        workspace.resolve(
            workspace.getBuckPaths().getGenDir().resolve("main_module_with_prebuilt_dep_bin.pex"));

    ImmutableSet<Path> expectedPaths =
        ImmutableSet.of(
            Paths.get("wheel_package", "my_wheel.py"),
            Paths.get("wheel_package", "__init__.py"),
            Paths.get("wheel_package-0.0.1.dist-info", "DESCRIPTION.rst"),
            Paths.get("lib", "foo", "bar.py"));
    ImmutableSet<Path> expectedAbsentPaths =
        ImmutableSet.of(
            Paths.get(
                ".deps", "wheel_package-0.0.1-py2-none-any.whl", "wheel_package", "my_wheel.py"),
            Paths.get(
                ".deps", "wheel_package-0.0.1-py2-none-any.whl", "wheel_package", "__init__.py"),
            Paths.get(
                ".deps",
                "wheel_package-0.0.1-py2-none-any.whl",
                "wheel_package-0.0.1.dist-info",
                "DESCRIPTION.rst"),
            Paths.get(
                ".deps",
                "wheel_package-0.0.1-py2-none-any.whl",
                "wheel_package-0.0.1.data",
                "data",
                "lib",
                "foo",
                "bar.py"));
    ImmutableSet<Path> paths;
    if (pexDirectory) {
      paths =
          Files.walk(binPath)
              .filter(p -> !p.equals(binPath))
              .map(binPath::relativize)
              .collect(ImmutableSet.toImmutableSet());
    } else {
      paths = Unzip.getZipMembers(binPath);
    }
    assertThat(expectedPaths, everyItem(Matchers.in(paths)));
    assertThat(expectedAbsentPaths, everyItem(not(Matchers.in(paths))));
  }

  @Test
  public void inplacePackagePrebuiltLibrariesProperly() throws IOException, InterruptedException {
    assumeThat(packageStyle, Matchers.is(PackageStyle.INPLACE));

    workspace.runBuckCommand("run", "//:main_module_with_prebuilt_dep_bin").assertSuccess();

    Path linkTreeDir =
        workspace.resolve(
            workspace
                .getBuckPaths()
                .getGenDir()
                .resolve("main_module_with_prebuilt_dep_bin#link-tree"));
    Path originalWhlDir =
        workspace.resolve(
            workspace
                .getBuckPaths()
                .getGenDir()
                .resolve("external_sources")
                .resolve("__whl_dep__extracted"));

    ImmutableSet<Path> expectedPaths =
        ImmutableSet.of(
            Paths.get("wheel_package", "my_wheel.py"),
            Paths.get("wheel_package", "__init__.py"),
            Paths.get("wheel_package-0.0.1.dist-info", "DESCRIPTION.rst"),
            Paths.get("lib", "foo", "bar.py"));

    ImmutableSet<Path> expectedAbsentPaths =
        ImmutableSet.of(Paths.get("wheel_package-0.0.1.data", "data"));

    for (Path path : expectedPaths) {
      assertTrue(Files.exists(linkTreeDir.resolve(path)));
      assertTrue(Files.isSameFile(linkTreeDir.resolve(path), originalWhlDir.resolve(path)));
    }
    for (Path path : expectedAbsentPaths) {
      assertFalse(Files.exists(linkTreeDir.resolve(path)));
    }
    ImmutableList<String> links =
        Files.walk(linkTreeDir).map(Path::toString).collect(ImmutableList.toImmutableList());
    assertThat(links, everyItem(not(endsWith(".whl"))));
  }

  /**
   * Test a bug where a C/C++ library that is transitively excluded by a `python_library` containing
   * native extensions (in this case, it has to be a 2nd-order dep of the `python_library`) but
   * which is also a direct dependency of another Python rule, causes the node to be processed as
   * both a linkable root and an excluded rule, causing an internal omnibus failure.
   */
  @Test
  public void omnibusExcludedNativeLinkableRoot() throws IOException {
    assumeThat(nativeLinkStrategy, Matchers.is(NativeLinkStrategy.MERGED));
    workspace
        .runBuckCommand("targets", "--show-output", "//omnibus_excluded_root:bin")
        .assertSuccess();
  }

  private PythonBuckConfig getPythonBuckConfig() throws IOException {
    Config rawConfig = Configs.createDefaultConfig(tmp.getRoot());
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(tmp.getRoot()))
            .setSections(rawConfig.getRawConfig())
            .build();
    return new PythonBuckConfig(buckConfig);
  }

  @Test
  public void stripsPathEarlyInInplaceBinaries() throws IOException, InterruptedException {
    assumeThat(packageStyle, Matchers.is(PackageStyle.INPLACE));
    Path pexPath = workspace.buildAndReturnOutput("//pathtest:pathtest");

    DefaultProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());

    Result ret =
        executor.launchAndExecute(
            ProcessExecutorParams.builder()
                .setDirectory(workspace.resolve("pathtest"))
                .setCommand(ImmutableList.of(pexPath.toString()))
                .build());
    Assert.assertEquals(0, ret.getExitCode());
  }
}

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

package com.facebook.buck.features.ocaml;

import static com.facebook.buck.features.ocaml.OcamlRuleBuilder.createOcamlLinkTarget;
import static com.facebook.buck.features.ocaml.OcamlRuleBuilder.createStaticLibraryBuildTarget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.CxxSourceRuleFactoryHelper;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.DefaultCxxPlatforms;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class OCamlIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void checkOcamlIsConfigured() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "ocaml", tmp);
    workspace.setUp();

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());

    Config rawConfig = Configs.createDefaultConfig(filesystem.getRootPath());

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(rawConfig.getRawConfig())
            .build();

    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                CxxPlatformsProvider.DEFAULT_NAME,
                CxxPlatformsProvider.of(
                    CxxPlatformUtils.DEFAULT_PLATFORM, CxxPlatformUtils.DEFAULT_PLATFORMS))
            .build();

    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    ExecutableFinder executableFinder = new ExecutableFinder();
    ToolchainCreationContext toolchainCreationContext =
        ToolchainCreationContext.of(
            ImmutableMap.of(),
            buckConfig,
            new FakeProjectFilesystem(),
            processExecutor,
            executableFinder,
            TestRuleKeyConfigurationFactory.create());

    OcamlToolchainFactory factory = new OcamlToolchainFactory();
    Optional<OcamlToolchain> toolchain =
        factory.createToolchain(toolchainProvider, toolchainCreationContext);

    OcamlPlatform ocamlPlatform =
        toolchain.orElseThrow(AssertionError::new).getDefaultOcamlPlatform();

    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    try {
      ocamlPlatform.getOcamlCompiler().resolve(resolver).getCommandPrefix(pathResolver);
    } catch (HumanReadableException e) {
      assumeNoException(e);
    }
  }

  @Test
  public void testHelloOcamlBuild() throws IOException {
    BuildTarget target =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//hello_ocaml:hello_ocaml");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget lib =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//hello_ocaml:ocamllib");
    BuildTarget staticLib =
        createStaticLibraryBuildTarget(lib).withAppendedFlavors(DefaultCxxPlatforms.FLAVOR);
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, lib, staticLib);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib.toString());

    workspace.resetBuildLogFile();

    // Check that running a build again results in no builds since everything is up to
    // date.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/amodule.ml", "v2", "v3");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetHadMatchingRuleKey(staticLib.toString());

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/ocamllib/m1.ml", "print me", "print Me");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib.toString());

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/BUCK", "#INSERT_POINT", "'ocamllib/dummy.ml',");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib.toString());

    workspace.resetBuildLogFile();

    BuildTarget lib1 =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//hello_ocaml:ocamllib1");
    BuildTarget staticLib1 =
        createStaticLibraryBuildTarget(lib1).withAppendedFlavors(DefaultCxxPlatforms.FLAVOR);
    ImmutableSet<BuildTarget> targets1 = ImmutableSet.of(target, binary, lib1, staticLib1);
    // We rebuild if lib name changes
    workspace.replaceFileContents(
        "hello_ocaml/BUCK", "name = \"ocamllib\"", "name = \"ocamllib1\"");
    workspace.replaceFileContents("hello_ocaml/BUCK", ":ocamllib", ":ocamllib1");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets1));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib1.toString());
  }

  @Test
  public void testNativePlugin() throws Exception {
    // Build the plugin
    BuildTarget pluginTarget =
        BuildTargetFactory.newInstance(
            workspace.getDestPath(), "//ocaml_native_plugin:plugin#default");
    workspace.runBuckCommand("build", pluginTarget.toString()).assertSuccess();

    // Also build a test binary that we'll use to verify that the .cmxs file
    // works
    BuildTarget binTarget =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//ocaml_native_plugin:tester");
    workspace.runBuckCommand("build", binTarget.toString()).assertSuccess();

    Path ocamlNativePluginDir =
        workspace.getDestPath().resolve("buck-out").resolve("gen").resolve("ocaml_native_plugin");

    Path pluginCmxsFile = ocamlNativePluginDir.resolve("plugin#default").resolve("libplugin.cmxs");

    Path testerExecutableFile = ocamlNativePluginDir.resolve("tester").resolve("tester");

    // Run `./tester /path/to/plugin.cmxs`
    String out =
        workspace
            .runCommand(testerExecutableFile.toString(), pluginCmxsFile.toString())
            .getStdout()
            .get();

    assertEquals("it works!\n", out);
  }

  @Test
  public void testLexAndYaccBuild() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//calc:calc");
    BuildTarget binary = createOcamlLinkTarget(target);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(targets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("calc/lexer.mll", "The type token", "the type token");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(targets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("calc/parser.mly", "the entry point", "The entry point");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(targets, buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());
  }

  @Test
  public void testCInteropBuild() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//ctest:ctest");
    BuildTarget binary = createOcamlLinkTarget(target);
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/ctest.c", "NATIVE PLUS", "Native Plus");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/BUCK", "#INSERTION_POINT", "compiler_flags=['-noassert']");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents(
        "ctest/BUCK", "compiler_flags=['-noassert']", "compiler_flags=[]");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/BUCK", "compiler_flags=[]", "compiler_flags=[]");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(ImmutableSet.of(binary, target), buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());
  }

  @Test
  public void testSimpleBuildWithLib() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:plus");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
  }

  @Test
  public void testRootBuildTarget() throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:main");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
  }

  @Test
  @Ignore("Redesign test so it does not depend on compiler/platform-specific binary artifacts.")
  public void testPrebuiltLibraryBytecodeOnly() throws IOException {
    BuildTarget target =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//ocaml_ext_bc:ocaml_ext");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget bytecode = OcamlBuildRulesGenerator.addBytecodeFlavor(binary);
    BuildTarget libplus =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//ocaml_ext_bc:plus");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, bytecode, libplus);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    assertFalse(buildLog.getAllTargets().contains(binary));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(bytecode.toString());
  }

  @Test
  @Ignore("Redesign test so it does not depend on compiler/platform-specific binary artifacts.")
  public void testPrebuiltLibraryMac() throws IOException {
    if (Platform.detect() == Platform.MACOS) {
      BuildTarget target =
          BuildTargetFactory.newInstance(workspace.getDestPath(), "//ocaml_ext_mac:ocaml_ext");
      BuildTarget binary = createOcamlLinkTarget(target);
      BuildTarget bytecode = OcamlBuildRulesGenerator.addBytecodeFlavor(binary);
      BuildTarget libplus =
          BuildTargetFactory.newInstance(workspace.getDestPath(), "//ocaml_ext_mac:plus");
      ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, bytecode, libplus);

      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      BuckBuildLog buildLog = workspace.getBuildLog();
      for (BuildTarget t : targets) {
        assertTrue(
            String.format("Expected %s to be built", t.toString()),
            buildLog.getAllTargets().contains(t));
      }
      buildLog.assertTargetBuiltLocally(target.toString());
      buildLog.assertTargetBuiltLocally(binary.toString());

      workspace.resetBuildLogFile();
      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      for (BuildTarget t : targets) {
        assertTrue(
            String.format("Expected %s to be built", t.toString()),
            buildLog.getAllTargets().contains(t));
      }
      buildLog.assertTargetHadMatchingRuleKey(target.toString());
      buildLog.assertTargetHadMatchingRuleKey(binary.toString());

      workspace.resetBuildLogFile();
      workspace.replaceFileContents("ocaml_ext_mac/BUCK", "libplus_lib", "libplus_lib1");
      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      buildLog = workspace.getBuildLog();
      assertTrue(buildLog.getAllTargets().containsAll(targets));
      buildLog.assertTargetBuiltLocally(target.toString());
      buildLog.assertTargetBuiltLocally(binary.toString());
    }
  }

  @Test
  @Ignore("Redesign test so it does not depend on compiler/platform-specific binary artifacts.")
  public void testPrebuiltLibraryMacWithNativeBytecode() throws IOException {
    if (Platform.detect() == Platform.MACOS) {
      BuildTarget target =
          BuildTargetFactory.newInstance(
              workspace.getDestPath(), "//ocaml_ext_mac:ocaml_ext_native_bytecode");
      BuildTarget binary = createOcamlLinkTarget(target);
      BuildTarget bytecode = OcamlBuildRulesGenerator.addBytecodeFlavor(binary);
      BuildTarget libplus =
          BuildTargetFactory.newInstance(
              workspace.getDestPath(), "//ocaml_ext_mac:plus_native_bytecode");
      ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, bytecode, libplus);

      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      BuckBuildLog buildLog = workspace.getBuildLog();
      for (BuildTarget t : targets) {
        assertTrue(
            String.format("Expected %s to be built", t.toString()),
            buildLog.getAllTargets().contains(t));
      }
      buildLog.assertTargetBuiltLocally(target.toString());
      buildLog.assertTargetBuiltLocally(binary.toString());

      workspace.resetBuildLogFile();
      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      for (BuildTarget t : targets) {
        assertTrue(
            String.format("Expected %s to be built", t.toString()),
            buildLog.getAllTargets().contains(t));
      }
      buildLog.assertTargetHadMatchingRuleKey(target.toString());
      buildLog.assertTargetHadMatchingRuleKey(binary.toString());

      workspace.resetBuildLogFile();
      workspace.replaceFileContents("ocaml_ext_mac/BUCK", "libplus_lib", "libplus_lib1");
      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      buildLog = workspace.getBuildLog();
      assertTrue(buildLog.getAllTargets().containsAll(targets));
      buildLog.assertTargetBuiltLocally(target.toString());
      buildLog.assertTargetBuiltLocally(binary.toString());
    }
  }

  @Test
  public void testCppLibraryDependency() throws InterruptedException, IOException {
    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//clib:clib");
    BuildTarget binary = createOcamlLinkTarget(target);
    BuildTarget libplus = BuildTargetFactory.newInstance(workspace.getDestPath(), "//clib:plus");
    BuildTarget libplusStatic =
        createStaticLibraryBuildTarget(libplus).withAppendedFlavors(DefaultCxxPlatforms.FLAVOR);
    BuildTarget cclib = BuildTargetFactory.newInstance(workspace.getDestPath(), "//clib:cc");

    CxxPlatform cxxPlatform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));
    CxxSourceRuleFactory cxxSourceRuleFactory =
        CxxSourceRuleFactoryHelper.of(workspace.getDestPath(), cclib, cxxPlatform);
    BuildTarget cclibbin =
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
            cclib, cxxPlatform.getFlavor(), PicType.PDC);
    String sourceName = "cc/cc.cpp";
    BuildTarget ccObj = cxxSourceRuleFactory.createCompileBuildTarget(sourceName);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            cclib, HeaderVisibility.PRIVATE, cxxPlatform.getFlavor());
    BuildTarget exportedHeaderSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(
            cclib,
            HeaderVisibility.PUBLIC,
            CxxPlatformUtils.getHeaderModeForDefaultPlatform(tmp.getRoot()).getFlavor());

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(binary.toString());
    buildLog.assertTargetBuiltLocally(libplusStatic.toString());
    buildLog.assertTargetBuiltLocally(cclibbin.toString());
    buildLog.assertTargetBuiltLocally(ccObj.toString());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());
    buildLog.assertTargetBuiltLocally(exportedHeaderSymlinkTreeTarget.toString());

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("clib/cc/cc.cpp", "Hi there", "hi there");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(binary.toString());
    buildLog.assertTargetBuiltLocally(libplusStatic.toString());
    buildLog.assertTargetBuiltLocally(cclibbin.toString());
    buildLog.assertTargetBuiltLocally(ccObj.toString());
  }

  @Test
  public void testConfigWarningsFlags() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "config_warnings_flags", tmp.newFolder());
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:unused_var");
    BuildTarget binary = createOcamlLinkTarget(target);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertFailure();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetCanceled(target.toString());
    buildLog.assertTargetCanceled(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents(".buckconfig", "warnings_flags=+a", "");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());
  }

  @Test
  public void testConfigInteropIncludes() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "config_interop_includes", tmp.newFolder());
    workspace.setUp();

    Path ocamlc =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlc"), EnvVariablesProvider.getSystemEnv());

    ProcessExecutor.Result result = workspace.runCommand(ocamlc.toString(), "-where");
    assertEquals(0, result.getExitCode());
    String stdlibPath = result.getStdout().get();

    BuildTarget target = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:test");
    BuildTarget binary = createOcamlLinkTarget(target);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    // Points somewhere with no stdlib in it, so fails to find Pervasives
    workspace.runBuckCommand("build", target.toString()).assertFailure();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertThat(buildLog.getAllTargets(), Matchers.hasItems(targets.toArray(new BuildTarget[0])));
    buildLog.assertTargetCanceled(target.toString());
    buildLog.assertTargetCanceled(binary.toString());

    workspace.resetBuildLogFile();

    // Point to the real stdlib (from `ocamlc -where`)
    workspace.replaceFileContents(
        ".buckconfig", "interop.includes=lib", "interop.includes=" + stdlibPath);
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();

    // Remove the config, should default to a valid place
    workspace.replaceFileContents(".buckconfig", "interop.includes=" + stdlibPath, "");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());
  }

  @Test
  public void testGenruleDependency() throws IOException {
    BuildTarget binary =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//generated:binary");
    BuildTarget generated =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//generated:generated");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(binary, generated);

    // Build the binary.
    workspace.runBuckCommand("build", binary.toString()).assertSuccess();

    // Make sure the generated target is built as well.
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(binary.toString());
  }

  @Test
  public void testCompilerFlagsDependency() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "compiler_flag_macros", tmp.newFolder());
    workspace.setUp();

    String ocamlVersion = this.getOcamlVersion(workspace);
    assumeTrue("Installed ocaml is too old for this test", "4.02.0".compareTo(ocamlVersion) <= 0);

    BuildTarget binary = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:main");
    BuildTarget lib = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:lib");
    BuildTarget helper = BuildTargetFactory.newInstance(workspace.getDestPath(), "//:test");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(binary, lib, helper);

    // Build the binary.
    workspace.runBuckCommand("build", binary.toString()).assertSuccess();

    // Make sure the helper target is built as well.
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(binary.toString());

    // Make sure the ppx flag worked
    String out = workspace.runBuckCommand("run", binary.toString()).getStdout();
    assertEquals("42!\n", out);
  }

  @Test
  public void testOcamlDepFlagMacros() throws IOException {
    BuildTarget binary =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//ocamldep_flags:main");
    BuildTarget lib =
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//ocamldep_flags:code");
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(binary, lib);

    workspace.runBuckCommand("build", binary.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(binary.toString());

    // Make sure the ppx flag worked
    String out = workspace.runBuckCommand("run", binary.toString()).getStdout();
    assertEquals("142!\n", out);
  }

  private String getOcamlVersion(ProjectWorkspace workspace)
      throws IOException, InterruptedException {
    Path ocamlc =
        new ExecutableFinder(Platform.detect())
            .getExecutable(Paths.get("ocamlc"), EnvVariablesProvider.getSystemEnv());

    ProcessExecutor.Result result = workspace.runCommand(ocamlc.toString(), "-version");
    assertEquals(0, result.getExitCode());
    return result.getStdout().get();
  }
}

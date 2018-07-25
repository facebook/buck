/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.rules.macros.ClasspathMacro;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.rules.macros.WorkerMacro;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class GenruleTest {

  private ProjectFilesystem filesystem;

  @Before
  public void newFakeFilesystem() {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  /**
   * Quick class to create a self contained genrule (and the infra needed to get a rulekey), and to
   * get the rulekey. This doesn't let multiple targets in the same cache/graph, it's solely to help
   * generate standalone genrules
   */
  private class StandaloneGenruleBuilder {

    private final ActionGraphBuilder graphBuilder;
    private final DefaultRuleKeyFactory ruleKeyFactory;
    final GenruleBuilder genruleBuilder;

    StandaloneGenruleBuilder(String targetName) {
      graphBuilder = new TestActionGraphBuilder();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      ruleKeyFactory =
          new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);
      genruleBuilder = GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance(targetName));
    }

    RuleKey getRuleKey() {
      return ruleKeyFactory.build(genruleBuilder.build(graphBuilder));
    }
  }

  @Test
  public void testCreateAndRunGenrule() throws IOException, NoSuchBuildTargetException {
    /*
     * Programmatically build up a Genrule that corresponds to:
     *
     * genrule(
     *   name = 'katana_manifest',
     *   srcs = [
     *     'convert_to_katana.py',
     *     'AndroidManifest.xml',
     *   ],
     *   cmd = 'python $SRCDIR/* > $OUT',
     *   out = 'AndroidManifest.xml',
     * )
     */

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    createSampleJavaBinaryRule(graphBuilder);

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            filesystem.getRootPath(), "//src/com/facebook/katana:katana_manifest");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(buildTarget)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setCmdExe("python convert_to_katana.py AndroidManifest.xml > %OUT%")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem,
                        filesystem.getPath("src/com/facebook/katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem,
                        filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"))))
            .build(graphBuilder, filesystem);

    // Verify all of the observers of the Genrule.
    assertEquals(
        filesystem
            .getBuckPaths()
            .getGenDir()
            .resolve("src/com/facebook/katana/katana_manifest/AndroidManifest.xml"),
        pathResolver.getRelativePath(genrule.getSourcePathToOutput()));
    assertEquals(
        filesystem.resolve(
            filesystem
                .getBuckPaths()
                .getGenDir()
                .resolve("src/com/facebook/katana/katana_manifest/AndroidManifest.xml")),
        genrule.getAbsoluteOutputFilePath());
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(filesystem.getRootPath());
    ImmutableList<Path> inputsToCompareToOutputs =
        ImmutableList.of(
            filesystem.getPath("src/com/facebook/katana/convert_to_katana.py"),
            filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"));
    assertEquals(
        inputsToCompareToOutputs, pathResolver.filterInputsToCompareToOutput(genrule.getSrcs()));

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = genrule.getBuildSteps(buildContext, new FakeBuildableContext());

    MoreAsserts.assertStepsNames(
        "",
        ImmutableList.of(
            "rm", "mkdir", "rm", "mkdir", "rm", "mkdir", "genrule_srcs_link_tree", "genrule"),
        steps);

    ExecutionContext executionContext = newEmptyExecutionContext();

    assertEquals(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    filesystem,
                    filesystem
                        .getBuckPaths()
                        .getGenDir()
                        .resolve("src/com/facebook/katana/katana_manifest")))
            .withRecursive(true),
        steps.get(0));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                filesystem,
                filesystem
                    .getBuckPaths()
                    .getGenDir()
                    .resolve("src/com/facebook/katana/katana_manifest"))),
        steps.get(1));

    assertEquals(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    filesystem,
                    filesystem
                        .getBuckPaths()
                        .getGenDir()
                        .resolve("src/com/facebook/katana/katana_manifest__tmp")))
            .withRecursive(true),
        steps.get(2));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                filesystem,
                filesystem
                    .getBuckPaths()
                    .getGenDir()
                    .resolve("src/com/facebook/katana/katana_manifest__tmp"))),
        steps.get(3));

    Path pathToSrcDir =
        filesystem
            .getBuckPaths()
            .getGenDir()
            .resolve("src/com/facebook/katana/katana_manifest__srcs");
    assertEquals(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(), filesystem, pathToSrcDir))
            .withRecursive(true),
        steps.get(4));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), filesystem, pathToSrcDir)),
        steps.get(5));

    assertEquals(
        new SymlinkTreeStep(
            "genrule_srcs",
            filesystem,
            pathToSrcDir,
            ImmutableMap.of(
                filesystem.getPath("convert_to_katana.py"),
                filesystem.getPath("src/com/facebook/katana/convert_to_katana.py"),
                filesystem.getPath("AndroidManifest.xml"),
                filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"))),
        steps.get(6));

    Step eighthStep = steps.get(7);
    assertTrue(eighthStep instanceof AbstractGenruleStep);
    AbstractGenruleStep genruleCommand = (AbstractGenruleStep) eighthStep;
    assertEquals("genrule", genruleCommand.getShortName());
    assertEquals(
        ImmutableMap.<String, String>builder()
            .put(
                "OUT",
                filesystem
                    .resolve(
                        filesystem
                            .getBuckPaths()
                            .getGenDir()
                            .resolve("src/com/facebook/katana/katana_manifest/AndroidManifest.xml"))
                    .toString())
            .build(),
        genruleCommand.getEnvironmentVariables(executionContext));
    Path scriptFilePath = genruleCommand.getScriptFilePath(executionContext);
    String scriptFileContents = genruleCommand.getScriptFileContents(executionContext);
    if (Platform.detect() == Platform.WINDOWS) {
      assertEquals(
          ImmutableList.of(scriptFilePath.toString()),
          genruleCommand.getShellCommand(executionContext));
      assertEquals("python convert_to_katana.py AndroidManifest.xml > %OUT%", scriptFileContents);
    } else {
      assertEquals(
          ImmutableList.of("/bin/bash", "-e", scriptFilePath.toString()),
          genruleCommand.getShellCommand(executionContext));
      assertEquals("python convert_to_katana.py AndroidManifest.xml > $OUT", scriptFileContents);
    }
  }

  @Test
  public void testGenruleType() throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            filesystem.getRootPath(), "//src/com/facebook/katana:katana_manifest");
    BuildRule genrule =
        GenruleBuilder.newGenruleBuilder(buildTarget)
            .setOut("output.xml")
            .setType("xxxxx")
            .build(graphBuilder, filesystem);
    assertTrue(genrule.getType().contains("xxxxx"));
  }

  @Test
  public void testGenruleUsesSpacesForSrcsVariableDelimiterByDefault() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    SourcePath path1 = PathSourcePath.of(filesystem, filesystem.getPath("path1.txt"));
    SourcePath path2 = PathSourcePath.of(filesystem, filesystem.getPath("dir", "path2.txt"));

    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setSrcs(ImmutableList.of(path1, path2))
            .setCmd("echo \"Hello, world\" >> $OUT")
            .setOut("output.txt")
            .build(graphBuilder);

    String expected =
        String.format(
            "%s %s", pathResolver.getAbsolutePath(path1), pathResolver.getAbsolutePath(path2));
    ImmutableMap.Builder<String, String> actualEnvVarsBuilder = ImmutableMap.builder();

    genrule.addEnvironmentVariables(pathResolver, actualEnvVarsBuilder);

    assertEquals(expected, actualEnvVarsBuilder.build().get("SRCS"));
  }

  @Test
  public void testGenruleUsesProvidedDelimiterForSrcsVariable() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    SourcePath path1 = PathSourcePath.of(filesystem, filesystem.getPath("path 1.txt"));
    SourcePath path2 = PathSourcePath.of(filesystem, filesystem.getPath("dir name", "path 2.txt"));

    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setSrcs(ImmutableList.of(path1, path2))
            .setCmd("echo \"Hello, world\" >> $OUT")
            .setOut("output.txt")
            .setEnvironmentExpansionSeparator("//")
            .build(graphBuilder);

    String expected =
        String.format(
            "%s//%s", pathResolver.getAbsolutePath(path1), pathResolver.getAbsolutePath(path2));
    ImmutableMap.Builder<String, String> actualEnvVarsBuilder = ImmutableMap.builder();

    genrule.addEnvironmentVariables(pathResolver, actualEnvVarsBuilder);

    assertEquals(expected, actualEnvVarsBuilder.build().get("SRCS"));
  }

  private GenruleBuilder createGenruleBuilderThatUsesWorkerMacro(ActionGraphBuilder graphBuilder)
      throws NoSuchBuildTargetException {
    /*
     * Produces a GenruleBuilder that when built produces a Genrule that uses a $(worker) macro
     * that corresponds to:
     *
     * genrule(
     *   name = 'genrule_with_worker',
     *   srcs = [],
     *   cmd = '$(worker :worker_rule) abc',
     *   out = 'output.txt',
     * )
     *
     * worker_tool(
     *   name = 'worker_rule',
     *   exe = ':my_exe',
     * )
     *
     * sh_binary(
     *   name = 'my_exe',
     *   main = 'bin/exe',
     * );
     */
    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    DefaultWorkerTool workerTool =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .build(graphBuilder);
    workerTool.getBuildOutputInitializer().setBuildOutputForTests(UUID.randomUUID());

    return GenruleBuilder.newGenruleBuilder(
            BuildTargetFactory.newInstance("//:genrule_with_worker"))
        .setCmd(StringWithMacrosUtils.format("%s abc", WorkerMacro.of(workerTool.getBuildTarget())))
        .setOut("output.txt");
  }

  @Test
  public void testGenruleWithWorkerMacroUsesSpecialShellStep() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule genrule = createGenruleBuilderThatUsesWorkerMacro(graphBuilder).build(graphBuilder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    List<Step> steps =
        genrule.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    MoreAsserts.assertStepsNames(
        "", ImmutableList.of("rm", "mkdir", "rm", "mkdir", "rm", "mkdir", "worker"), steps);

    Step step = steps.get(6);
    assertTrue(step instanceof WorkerShellStep);
    WorkerShellStep workerShellStep = (WorkerShellStep) step;
    assertThat(workerShellStep.getShortName(), Matchers.equalTo("worker"));
    assertThat(
        workerShellStep.getEnvironmentVariables(),
        Matchers.hasEntry(
            "OUT",
            filesystem
                .resolve(filesystem.getBuckPaths().getGenDir())
                .resolve("genrule_with_worker/output.txt")
                .toString()));
  }

  @Test
  public void testIsWorkerGenruleReturnsTrue() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule = createGenruleBuilderThatUsesWorkerMacro(graphBuilder).build(graphBuilder);
    assertTrue(genrule.isWorkerGenrule());
  }

  @Test
  public void testIsWorkerGenruleReturnsFalse() throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:genrule_no_worker"))
            .setCmd("echo hello >> $OUT")
            .setOut("output.txt")
            .build(graphBuilder, filesystem);
    assertFalse(genrule.isWorkerGenrule());
  }

  @Test
  public void testConstructingGenruleWithBadWorkerMacroThrows() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    GenruleBuilder genruleBuilder = createGenruleBuilderThatUsesWorkerMacro(graphBuilder);
    try {
      genruleBuilder.setBash("no worker macro here").build(graphBuilder);
    } catch (HumanReadableException e) {
      assertEquals(
          "You cannot use a worker macro in one of the cmd, bash, or "
              + "cmd_exe properties and not in the others for genrule //:genrule_with_worker.",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testGenruleWithWorkerMacroIncludesWorkerToolInDeps()
      throws NoSuchBuildTargetException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(FakeSourcePath.of("bin/exe"))
            .build(graphBuilder);

    BuildRule workerToolRule =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .build(graphBuilder);

    BuildRule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule_with_worker"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s abs", WorkerMacro.of(workerToolRule.getBuildTarget())))
            .setOut("output.txt")
            .build(graphBuilder);

    assertThat(genrule.getBuildDeps(), Matchers.hasItems(shBinaryRule, workerToolRule));
  }

  private ExecutionContext newEmptyExecutionContext(Platform platform) {
    return TestExecutionContext.newBuilder()
        .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
        .setPlatform(platform)
        .build();
  }

  private ExecutionContext newEmptyExecutionContext() {
    return newEmptyExecutionContext(Platform.detect());
  }

  @Test
  public void ensureFilesInSubdirectoriesAreKeptInSubDirectories() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(filesystem.getRootPath());
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:example");
    Genrule rule =
        GenruleBuilder.newGenruleBuilder(target, filesystem)
            .setBash("ignored")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(filesystem, filesystem.getPath("in-dir.txt")),
                    PathSourcePath.of(filesystem, filesystem.getPath("foo/bar.html")),
                    PathSourcePath.of(filesystem, filesystem.getPath("other/place.txt"))))
            .setOut("example-file")
            .build(graphBuilder);

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    rule.addSymlinkCommands(context, builder);
    ImmutableList<Step> commands = builder.build();

    Path baseTmpPath = filesystem.getBuckPaths().getGenDir().resolve("example__srcs");

    MoreAsserts.assertStepsNames("", ImmutableList.of("genrule_srcs_link_tree"), commands);

    assertEquals(
        new SymlinkTreeStep(
            "genrule_srcs",
            filesystem,
            baseTmpPath,
            ImmutableMap.of(
                filesystem.getPath("in-dir.txt"),
                filesystem.getPath("in-dir.txt"),
                filesystem.getPath("foo/bar.html"),
                filesystem.getPath("foo/bar.html"),
                filesystem.getPath("other/place.txt"),
                filesystem.getPath("other/place.txt"))),
        commands.get(0));
  }

  private BuildRule createSampleJavaBinaryRule(ActionGraphBuilder graphBuilder)
      throws NoSuchBuildTargetException {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildRule javaLibrary =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
            .addSrc(Paths.get("java/com/facebook/util/ManifestGenerator.java"))
            .build(graphBuilder);

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    return new JavaBinaryRuleBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.of(javaLibrary.getBuildTarget()))
        .setMainClass("com.facebook.util.ManifestGenerator")
        .build(graphBuilder);
  }

  @Test
  public void testShouldIncludeAndroidSpecificEnvInEnvironmentIfPresent() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    AndroidPlatformTarget android =
        AndroidPlatformTarget.of(
            "android",
            Paths.get(""),
            Collections.emptyList(),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get("zipalign"),
            Paths.get("."),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""));
    Path sdkDir = Paths.get("/opt/users/android_sdk");
    Path ndkDir = Paths.get("/opt/users/android_ndk");

    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(AndroidPlatformTarget.DEFAULT_NAME, android)
            .withToolchain(
                AndroidNdk.DEFAULT_NAME, AndroidNdk.of("12", ndkDir, false, new ExecutableFinder()))
            .withToolchain(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.of(sdkDir))
            .build();
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target, toolchainProvider)
            .setBash("echo something > $OUT")
            .setOut("file")
            .build(graphBuilder);

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    genrule.addEnvironmentVariables(pathResolver, builder);
    ImmutableMap<String, String> env = builder.build();

    assertEquals(Paths.get(".").toString(), env.get("DX"));
    assertEquals(Paths.get("zipalign").toString(), env.get("ZIPALIGN"));
    assertEquals(sdkDir.toString(), env.get("ANDROID_HOME"));
    assertEquals(ndkDir.toString(), env.get("NDK_HOME"));
  }

  @Test
  public void shouldPreventTheParentBuckdBeingUsedIfARecursiveBuckCallIsMade() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target)
            .setBash("echo something > $OUT")
            .setOut("file")
            .build(graphBuilder);

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    genrule.addEnvironmentVariables(pathResolver, builder);

    assertEquals("1", builder.build().get("NO_BUCKD"));
  }

  @Test
  public void testGetShellCommand() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder)));
    String bash = "rm -rf /usr";
    String cmdExe = "rmdir /s /q C:\\Windows";
    String cmd = "echo \"Hello\"";
    ExecutionContext linuxExecutionContext = newEmptyExecutionContext(Platform.LINUX);
    ExecutionContext windowsExecutionContext = newEmptyExecutionContext(Platform.WINDOWS);

    // Test platform-specific
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule1"))
            .setBash(bash)
            .setCmdExe(cmdExe)
            .setOut("out.txt")
            .build(graphBuilder);

    assertGenruleCommandAndScript(
        genrule.createGenruleStep(buildContext),
        linuxExecutionContext,
        ImmutableList.of("/bin/bash", "-e"),
        bash);

    assertGenruleCommandAndScript(
        genrule.createGenruleStep(buildContext),
        windowsExecutionContext,
        ImmutableList.of(),
        cmdExe);

    // Test fallback
    genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule2"))
            .setCmd(cmd)
            .setOut("out.txt")
            .build(graphBuilder);
    assertGenruleCommandAndScript(
        genrule.createGenruleStep(buildContext),
        linuxExecutionContext,
        ImmutableList.of("/bin/bash", "-e"),
        cmd);

    assertGenruleCommandAndScript(
        genrule.createGenruleStep(buildContext), windowsExecutionContext, ImmutableList.of(), cmd);

    // Test command absent
    genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule3"))
            .setOut("out.txt")
            .build(graphBuilder);
    try {
      genrule.createGenruleStep(buildContext).getShellCommand(linuxExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(
          "You must specify either bash or cmd for genrule.", e.getHumanReadableErrorMessage());
    }

    try {
      genrule.createGenruleStep(buildContext).getShellCommand(windowsExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(
          "You must specify either cmd_exe or cmd for genrule.", e.getHumanReadableErrorMessage());
    }
  }

  private void assertGenruleCommandAndScript(
      AbstractGenruleStep genruleStep,
      ExecutionContext context,
      ImmutableList<String> expectedCommandPrefix,
      String expectedScriptFileContents)
      throws IOException {
    Path scriptFilePath = genruleStep.getScriptFilePath(context);
    String actualContents = genruleStep.getScriptFileContents(context);
    assertThat(actualContents, Matchers.equalTo(expectedScriptFileContents));
    ImmutableList<String> expectedCommand =
        ImmutableList.<String>builder()
            .addAll(expectedCommandPrefix)
            .add(scriptFilePath.toString())
            .build();
    ImmutableList<String> actualCommand = genruleStep.getShellCommand(context);
    assertThat(actualCommand, Matchers.equalTo(expectedCommand));
  }

  @Test
  public void testGetOutputNameMethod() {
    {
      String name = "out.txt";
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
              .setOut(name)
              .build(new TestActionGraphBuilder());
      assertEquals(name, genrule.getOutputName());
    }
    {
      String name = "out/file.txt";
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
              .setOut(name)
              .build(new TestActionGraphBuilder());
      assertEquals(name, genrule.getOutputName());
    }
  }

  @Test
  public void thatChangingOutChangesRuleKey() {
    StandaloneGenruleBuilder builder1 = new StandaloneGenruleBuilder("//:genrule1");
    StandaloneGenruleBuilder builder2 = new StandaloneGenruleBuilder("//:genrule1");

    builder1.genruleBuilder.setOut("foo");
    RuleKey key1 = builder1.getRuleKey();

    builder2.genruleBuilder.setOut("bar");
    RuleKey key2 = builder2.getRuleKey();

    // Verify that just the difference in output name is enough to make the rule key different.
    assertNotEquals(key1, key2);
  }

  @Test
  public void thatChangingCacheabilityChangesRuleKey() {
    StandaloneGenruleBuilder builder1 = new StandaloneGenruleBuilder("//:genrule1");
    StandaloneGenruleBuilder builder2 = new StandaloneGenruleBuilder("//:genrule1");

    builder1.genruleBuilder.setOut("foo").setCacheable(true);
    RuleKey key1 = builder1.getRuleKey();

    builder2.genruleBuilder.setOut("foo").setCacheable(false);
    RuleKey key2 = builder2.getRuleKey();

    assertNotEquals(key1, key2);
  }

  @Test
  public void inputBasedRuleKeyLocationMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    GenruleBuilder ruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "run %s", LocationMacro.of(BuildTargetFactory.newInstance("//:dep"))))
            .setOut("output");

    // Create an initial input-based rule key
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("dep.out")
            .setCmd("something")
            .build(graphBuilder);
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(graphBuilder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = ruleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the genrule's command, which will change its normal rule key, but since we're keeping
    // its output the same, the input-based rule key for the consuming rule will stay the same.
    // This is because the input-based rule key for the consuming rule only cares about the contents
    // of the output this rule produces.
    graphBuilder = new TestActionGraphBuilder();
    GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
        .setOut("dep.out")
        .setCmd("something else")
        .build(graphBuilder);
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey unchangedRuleKey = ruleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, Matchers.not(Matchers.equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, Matchers.equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    graphBuilder = new TestActionGraphBuilder();
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("dep.out")
            .setCmd("something")
            .build(graphBuilder);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(graphBuilder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey changedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(changedInputBasedRuleKey, Matchers.not(Matchers.equalTo(originalInputRuleKey)));
  }

  @Test
  public void inputBasedRuleKeyExecutableMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    GenruleBuilder ruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "run %s", ExecutableMacro.of(BuildTargetFactory.newInstance("//:dep"))))
            .setOut("output");

    // Create an initial input-based rule key
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule dep =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setMain(PathSourcePath.of(filesystem, Paths.get("dep.exe")))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath("something", Paths.get("dep.exe"));
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(graphBuilder);
    DefaultRuleKeyFactory defaultRuleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the dep's resource list, which will change its normal rule key, but since we're
    // keeping its output the same, the input-based rule key for the consuming rule will stay the
    // same.  This is because the input-based rule key for the consuming rule only cares about the
    // contents of the output this rule produces.
    graphBuilder = new TestActionGraphBuilder();
    Genrule extra =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:extra"))
            .setOut("something")
            .build(graphBuilder);
    new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
        .setMain(PathSourcePath.of(filesystem, Paths.get("dep.exe")))
        .setDeps(ImmutableSortedSet.of(extra.getBuildTarget()))
        .build(graphBuilder, filesystem);
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    defaultRuleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey unchangedRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, Matchers.not(Matchers.equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, Matchers.equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    graphBuilder = new TestActionGraphBuilder();
    dep =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setMain(PathSourcePath.of(filesystem, Paths.get("dep.exe")))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey changedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(changedInputBasedRuleKey, Matchers.not(Matchers.equalTo(originalInputRuleKey)));
  }

  @Test
  public void inputBasedRuleKeyClasspathMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    GenruleBuilder ruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "run %s", ClasspathMacro.of(BuildTargetFactory.newInstance("//:dep"))))
            .setOut("output");

    // Create an initial input-based rule key
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    JavaLibrary dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("source.java"))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath("something", Paths.get("source.java"));
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(graphBuilder);
    DefaultRuleKeyFactory defaultRuleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the dep's resource root, which will change its normal rule key, but since we're
    // keeping its output JAR the same, the input-based rule key for the consuming rule will stay
    // the same.  This is because the input-based rule key for the consuming rule only cares about
    // the contents of the output this rule produces.
    graphBuilder = new TestActionGraphBuilder();
    JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
        .addSrc(Paths.get("source.java"))
        .setResourcesRoot(Paths.get("resource_root"))
        .build(graphBuilder, filesystem);
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    defaultRuleKeyFactory =
        new TestDefaultRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey unchangedRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, Matchers.not(Matchers.equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, Matchers.equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    graphBuilder = new TestActionGraphBuilder();
    dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("source.java"))
            .build(graphBuilder, filesystem);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(graphBuilder);
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT),
            pathResolver,
            ruleFinder);
    RuleKey changedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(changedInputBasedRuleKey, Matchers.not(Matchers.equalTo(originalInputRuleKey)));
  }

  @Test
  public void isCacheableIsRespected() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget1 =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//katana:katana_manifest1");
    BuildTarget buildTarget2 =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//katana:katana_manifest2");
    BuildTarget buildTarget3 =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//katana:katana_manifest3");

    Genrule genrule1 =
        GenruleBuilder.newGenruleBuilder(buildTarget1)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/AndroidManifest.xml"))))
            .setCacheable(null)
            .build(graphBuilder, filesystem);

    Genrule genrule2 =
        GenruleBuilder.newGenruleBuilder(buildTarget2)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/AndroidManifest.xml"))))
            .setCacheable(true)
            .build(graphBuilder, filesystem);

    Genrule genrule3 =
        GenruleBuilder.newGenruleBuilder(buildTarget3)
            .setBash("python convert_to_katana.py AndroidManifest.xml > $OUT")
            .setOut("AndroidManifest.xml")
            .setSrcs(
                ImmutableList.of(
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/convert_to_katana.py")),
                    PathSourcePath.of(
                        filesystem, filesystem.getPath("katana/AndroidManifest.xml"))))
            .setCacheable(false)
            .build(graphBuilder, filesystem);

    assertTrue(genrule1.isCacheable());
    assertTrue(genrule2.isCacheable());
    assertFalse(genrule3.isCacheable());
  }

  @Test
  public void testChangingNoRemoteChangesRuleKey() {
    StandaloneGenruleBuilder builder1 = new StandaloneGenruleBuilder("//:genrule1");
    StandaloneGenruleBuilder builder2 = new StandaloneGenruleBuilder("//:genrule1");

    builder1.genruleBuilder.setOut("foo").setNoRemote(true);
    RuleKey key1 = builder1.getRuleKey();

    builder2.genruleBuilder.setOut("foo").setNoRemote(false);
    RuleKey key2 = builder2.getRuleKey();

    assertNotEquals(key1, key2);
  }

  @Test
  public void testNoRemoteIsRespected() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget buildTarget1 = BuildTargetFactory.newInstance("//:genrule1");
    BuildTarget buildTarget2 = BuildTargetFactory.newInstance("//:genrule2");
    BuildTarget buildTarget3 = BuildTargetFactory.newInstance("//:genrule3");

    Genrule genrule1 =
        GenruleBuilder.newGenruleBuilder(buildTarget1)
            .setOut("foo")
            .setNoRemote(null)
            .build(graphBuilder);
    Genrule genrule2 =
        GenruleBuilder.newGenruleBuilder(buildTarget2)
            .setOut("foo")
            .setNoRemote(false)
            .build(graphBuilder);
    Genrule genrule3 =
        GenruleBuilder.newGenruleBuilder(buildTarget3)
            .setOut("foo")
            .setNoRemote(true)
            .build(graphBuilder);

    assertFalse(genrule1.shouldBuildLocally());
    assertFalse(genrule2.shouldBuildLocally());
    assertTrue(genrule3.shouldBuildLocally());
  }
}

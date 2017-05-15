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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildInfo;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class GenruleTest {

  private ProjectFilesystem filesystem;

  @Before
  public void newFakeFilesystem() throws InterruptedException {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
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

    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    createSampleJavaBinaryRule(ruleResolver);

    // From the Python object, create a GenruleBuildRuleFactory to create a Genrule.Builder
    // that builds a Genrule from the Python object.
    //    BuildTargetParser parser = BuildTargetParser.INSTANCE;
    //    EasyMock.expect(parser.parse(EasyMock.eq("//java/com/facebook/util:util"),
    //        EasyMock.anyObject(BuildTargetPatternParser.class)))
    //        .andStubReturn(BuildTargetFactory.newInstance("//java/com/facebook/util:util"));
    //    EasyMock.replay(parser);

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
                    new PathSourcePath(
                        filesystem,
                        filesystem.getPath("src/com/facebook/katana/convert_to_katana.py")),
                    new PathSourcePath(
                        filesystem,
                        filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"))))
            .build(ruleResolver, filesystem);

    // Verify all of the observers of the Genrule.
    assertEquals(
        filesystem
            .getBuckPaths()
            .getGenDir()
            .resolve("src/com/facebook/katana/katana_manifest/AndroidManifest.xml"),
        pathResolver.getRelativePath(genrule.getSourcePathToOutput()));
    assertEquals(
        filesystem
            .resolve(
                filesystem
                    .getBuckPaths()
                    .getGenDir()
                    .resolve("src/com/facebook/katana/katana_manifest/AndroidManifest.xml"))
            .toString(),
        genrule.getAbsoluteOutputFilePath(pathResolver));
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    ImmutableList<Path> inputsToCompareToOutputs =
        ImmutableList.of(
            filesystem.getPath("src/com/facebook/katana/convert_to_katana.py"),
            filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"));
    assertEquals(
        inputsToCompareToOutputs, pathResolver.filterInputsToCompareToOutput(genrule.getSrcs()));

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = genrule.getBuildSteps(buildContext, new FakeBuildableContext());
    assertEquals(11, steps.size());

    ExecutionContext executionContext = newEmptyExecutionContext();

    assertEquals(
        RmStep.of(
                filesystem,
                filesystem
                    .getBuckPaths()
                    .getGenDir()
                    .resolve("src/com/facebook/katana/katana_manifest"))
            .withRecursive(true),
        steps.get(0));
    assertEquals(
        MkdirStep.of(
            filesystem,
            filesystem
                .getBuckPaths()
                .getGenDir()
                .resolve("src/com/facebook/katana/katana_manifest")),
        steps.get(1));

    assertEquals(
        RmStep.of(
                filesystem,
                filesystem
                    .getBuckPaths()
                    .getGenDir()
                    .resolve("src/com/facebook/katana/katana_manifest__tmp"))
            .withRecursive(true),
        steps.get(2));
    assertEquals(
        MkdirStep.of(
            filesystem,
            filesystem
                .getBuckPaths()
                .getGenDir()
                .resolve("src/com/facebook/katana/katana_manifest__tmp")),
        steps.get(3));

    Path pathToSrcDir =
        filesystem
            .getBuckPaths()
            .getGenDir()
            .resolve("src/com/facebook/katana/katana_manifest__srcs");
    assertEquals(RmStep.of(filesystem, pathToSrcDir).withRecursive(true), steps.get(4));
    assertEquals(MkdirStep.of(filesystem, pathToSrcDir), steps.get(5));

    assertEquals(MkdirStep.of(filesystem, pathToSrcDir), steps.get(6));
    assertEquals(
        SymlinkFileStep.builder()
            .setFilesystem(filesystem)
            .setExistingFile(filesystem.getPath("src/com/facebook/katana/convert_to_katana.py"))
            .setDesiredLink(filesystem.getPath(pathToSrcDir + "/convert_to_katana.py"))
            .build(),
        steps.get(7));

    assertEquals(MkdirStep.of(filesystem, pathToSrcDir), steps.get(8));
    assertEquals(
        SymlinkFileStep.builder()
            .setFilesystem(filesystem)
            .setExistingFile(filesystem.getPath("src/com/facebook/katana/AndroidManifest.xml"))
            .setDesiredLink(filesystem.getPath(pathToSrcDir + "/AndroidManifest.xml"))
            .build(),
        steps.get(9));

    Step sixthStep = steps.get(10);
    assertTrue(sixthStep instanceof AbstractGenruleStep);
    AbstractGenruleStep genruleCommand = (AbstractGenruleStep) sixthStep;
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
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            filesystem.getRootPath(), "//src/com/facebook/katana:katana_manifest");
    BuildRule genrule =
        GenruleBuilder.newGenruleBuilder(buildTarget)
            .setOut("output.xml")
            .setType("xxxxx")
            .build(ruleResolver, filesystem);
    assertTrue(genrule.getType().contains("xxxxx"));
  }

  private GenruleBuilder createGenruleBuilderThatUsesWorkerMacro(BuildRuleResolver resolver)
      throws NoSuchBuildTargetException, IOException {
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
            .setMain(new FakeSourcePath("bin/exe"))
            .build(resolver);

    DefaultWorkerTool workerTool =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .build(resolver);
    workerTool
        .getBuildOutputInitializer()
        .setBuildOutput(
            workerTool.initializeFromDisk(
                new FakeOnDiskBuildInfo()
                    .putMetadata(
                        BuildInfo.MetadataKey.RULE_KEY, Hashing.sha1().hashLong(0).toString())));

    return GenruleBuilder.newGenruleBuilder(
            BuildTargetFactory.newInstance("//:genrule_with_worker"))
        .setCmd("$(worker :worker_rule) abc")
        .setOut("output.txt");
  }

  @Test
  public void testGenruleWithWorkerMacroUsesSpecialShellStep() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    Genrule genrule = createGenruleBuilderThatUsesWorkerMacro(ruleResolver).build(ruleResolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    List<Step> steps =
        genrule.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    ExecutionContext executionContext = newEmptyExecutionContext(Platform.LINUX);

    assertEquals(7, steps.size());
    Step step = steps.get(6);
    assertTrue(step instanceof WorkerShellStep);
    WorkerShellStep workerShellStep = (WorkerShellStep) step;
    assertThat(workerShellStep.getShortName(), Matchers.equalTo("worker"));
    assertThat(
        workerShellStep.getEnvironmentVariables(executionContext),
        Matchers.hasEntry(
            "OUT",
            filesystem
                .resolve(filesystem.getBuckPaths().getGenDir())
                .resolve("genrule_with_worker/output.txt")
                .toString()));
  }

  @Test
  public void testIsWorkerGenruleReturnsTrue() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    Genrule genrule = createGenruleBuilderThatUsesWorkerMacro(ruleResolver).build(ruleResolver);
    assertTrue(genrule.isWorkerGenrule());
  }

  @Test
  public void testIsWorkerGenruleReturnsFalse() throws NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:genrule_no_worker"))
            .setCmd("echo hello >> $OUT")
            .setOut("output.txt")
            .build(ruleResolver, filesystem);
    assertFalse(genrule.isWorkerGenrule());
  }

  @Test
  public void testConstructingGenruleWithBadWorkerMacroThrows() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    GenruleBuilder genruleBuilder = createGenruleBuilderThatUsesWorkerMacro(ruleResolver);
    try {
      genruleBuilder.setBash("no worker macro here").build(ruleResolver);
    } catch (HumanReadableException e) {
      assertEquals(
          String.format(
              "You cannot use a worker macro in one of the cmd, bash, or "
                  + "cmd_exe properties and not in the others for genrule //:genrule_with_worker."),
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testGenruleWithWorkerMacroIncludesWorkerToolInDeps()
      throws NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule shBinaryRule =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:my_exe"))
            .setMain(new FakeSourcePath("bin/exe"))
            .build(ruleResolver);

    BuildRule workerToolRule =
        WorkerToolBuilder.newWorkerToolBuilder(BuildTargetFactory.newInstance("//:worker_rule"))
            .setExe(shBinaryRule.getBuildTarget())
            .build(ruleResolver);

    BuildRule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule_with_worker"))
            .setCmd("$(worker :worker_rule) abc")
            .setOut("output.txt")
            .build(ruleResolver);

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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:example");
    Genrule rule =
        GenruleBuilder.newGenruleBuilder(target, filesystem)
            .setBash("ignored")
            .setSrcs(
                ImmutableList.of(
                    new PathSourcePath(filesystem, filesystem.getPath("in-dir.txt")),
                    new PathSourcePath(filesystem, filesystem.getPath("foo/bar.html")),
                    new PathSourcePath(filesystem, filesystem.getPath("other/place.txt"))))
            .setOut("example-file")
            .build(resolver);

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    rule.addSymlinkCommands(FakeBuildContext.withSourcePathResolver(pathResolver), builder);
    ImmutableList<Step> commands = builder.build();

    Path baseTmpPath = filesystem.getBuckPaths().getGenDir().resolve("example__srcs");

    assertEquals(6, commands.size());
    assertEquals(MkdirStep.of(filesystem, baseTmpPath), commands.get(0));
    assertEquals(
        SymlinkFileStep.builder()
            .setFilesystem(filesystem)
            .setExistingFile(filesystem.getPath("in-dir.txt"))
            .setDesiredLink(baseTmpPath.resolve("in-dir.txt"))
            .build(),
        commands.get(1));

    assertEquals(MkdirStep.of(filesystem, baseTmpPath.resolve("foo")), commands.get(2));
    assertEquals(
        SymlinkFileStep.builder()
            .setFilesystem(filesystem)
            .setExistingFile(filesystem.getPath("foo", "bar.html"))
            .setDesiredLink(baseTmpPath.resolve("foo").resolve("bar.html"))
            .build(),
        commands.get(3));

    assertEquals(MkdirStep.of(filesystem, baseTmpPath.resolve("other")), commands.get(4));
    assertEquals(
        SymlinkFileStep.builder()
            .setFilesystem(filesystem)
            .setExistingFile(filesystem.getPath("other", "place.txt"))
            .setDesiredLink(baseTmpPath.resolve("other").resolve("place.txt"))
            .build(),
        commands.get(5));
  }

  private BuildRule createSampleJavaBinaryRule(BuildRuleResolver ruleResolver)
      throws NoSuchBuildTargetException {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildRule javaLibrary =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
            .addSrc(Paths.get("java/com/facebook/util/ManifestGenerator.java"))
            .build(ruleResolver);

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator");
    return new JavaBinaryRuleBuilder(buildTarget)
        .setDeps(ImmutableSortedSet.of(javaLibrary.getBuildTarget()))
        .setMainClass("com.facebook.util.ManifestGenerator")
        .build(ruleResolver);
  }

  @Test
  public void testShouldIncludeAndroidSpecificEnvInEnvironmentIfPresent() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    AndroidPlatformTarget android = EasyMock.createNiceMock(AndroidPlatformTarget.class);
    Path sdkDir = Paths.get("/opt/users/android_sdk");
    Path ndkDir = Paths.get("/opt/users/android_ndk");
    EasyMock.expect(android.getSdkDirectory()).andStubReturn(Optional.of(sdkDir));
    EasyMock.expect(android.getNdkDirectory()).andStubReturn(Optional.of(ndkDir));
    EasyMock.expect(android.getDxExecutable()).andStubReturn(Paths.get("."));
    EasyMock.expect(android.getZipalignExecutable()).andStubReturn(Paths.get("zipalign"));
    EasyMock.replay(android);

    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target)
            .setBash("echo something > $OUT")
            .setOut("file")
            .build(resolver);

    ExecutionContext context =
        TestExecutionContext.newBuilder()
            .setAndroidPlatformTargetSupplier(Suppliers.ofInstance(android))
            .build();

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    genrule.addEnvironmentVariables(pathResolver, context, builder);
    ImmutableMap<String, String> env = builder.build();

    assertEquals(Paths.get(".").toString(), env.get("DX"));
    assertEquals(Paths.get("zipalign").toString(), env.get("ZIPALIGN"));
    assertEquals(sdkDir.toString(), env.get("ANDROID_HOME"));
    assertEquals(ndkDir.toString(), env.get("NDK_HOME"));

    EasyMock.verify(android);
  }

  @Test
  public void shouldPreventTheParentBuckdBeingUsedIfARecursiveBuckCallIsMade() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(target)
            .setBash("echo something > $OUT")
            .setOut("file")
            .build(resolver);

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    genrule.addEnvironmentVariables(pathResolver, TestExecutionContext.newInstance(), builder);

    assertEquals("1", builder.build().get("NO_BUCKD"));
  }

  @Test
  public void testGetShellCommand() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(
            new SourcePathResolver(new SourcePathRuleFinder(resolver)));
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
            .build(resolver);

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
            .build(resolver);
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
            .build(resolver);
    try {
      genrule.createGenruleStep(buildContext).getShellCommand(linuxExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(
          String.format(
              "You must specify either bash or cmd for genrule %s.", genrule.getBuildTarget()),
          e.getHumanReadableErrorMessage());
    }

    try {
      genrule.createGenruleStep(buildContext).getShellCommand(windowsExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(
          String.format(
              "You must specify either cmd_exe or cmd for genrule %s.", genrule.getBuildTarget()),
          e.getHumanReadableErrorMessage());
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
  public void testGetOutputNameMethod() throws Exception {
    {
      String name = "out.txt";
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
              .setOut(name)
              .build(
                  new BuildRuleResolver(
                      TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
      assertEquals(name, genrule.getOutputName());
    }
    {
      String name = "out/file.txt";
      Genrule genrule =
          GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
              .setOut(name)
              .build(
                  new BuildRuleResolver(
                      TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
      assertEquals(name, genrule.getOutputName());
    }
  }

  @Test
  public void thatChangingOutChangesRuleKey() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver, ruleFinder);

    // Get a rule key for two genrules using two different output names, but are otherwise the
    // same.

    RuleKey key1 =
        ruleKeyFactory.build(
            GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule1"))
                .setOut("foo")
                .build(resolver));

    RuleKey key2 =
        ruleKeyFactory.build(
            GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule2"))
                .setOut("bar")
                .build(resolver));

    // Verify that just the difference in output name is enough to make the rule key different.
    assertNotEquals(key1, key2);
  }

  @Test
  public void inputBasedRuleKeyLocationMacro() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    GenruleBuilder ruleBuilder =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd("run $(location //:dep)")
            .setOut("output");

    // Create an initial input-based rule key
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("dep.out")
            .setCmd("something")
            .build(resolver);
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(resolver);
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = ruleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the genrule's command, which will change its normal rule key, but since we're keeping
    // its output the same, the input-based rule key for the consuming rule will stay the same.
    // This is because the input-based rule key for the consuming rule only cares about the contents
    // of the output this rule produces.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
        .setOut("dep.out")
        .setCmd("something else")
        .build(resolver);
    rule = ruleBuilder.build(resolver);
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    ruleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    RuleKey unchangedRuleKey = ruleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, Matchers.not(Matchers.equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, Matchers.equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("dep.out")
            .setCmd("something")
            .build(resolver);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(resolver);
    inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
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
            .setCmd("run $(exe //:dep)")
            .setOut("output");

    // Create an initial input-based rule key
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildRule dep =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setMain(new PathSourcePath(filesystem, Paths.get("dep.exe")))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("something", Paths.get("dep.exe"));
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(resolver);
    DefaultRuleKeyFactory defaultRuleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the dep's resource list, which will change its normal rule key, but since we're
    // keeping its output the same, the input-based rule key for the consuming rule will stay the
    // same.  This is because the input-based rule key for the consuming rule only cares about the
    // contents of the output this rule produces.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    Genrule extra =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:extra"))
            .setOut("something")
            .build(resolver);
    new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
        .setMain(new PathSourcePath(filesystem, Paths.get("dep.exe")))
        .setDeps(ImmutableSortedSet.of(extra.getBuildTarget()))
        .build(resolver, filesystem);
    rule = ruleBuilder.build(resolver);
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    defaultRuleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    RuleKey unchangedRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, Matchers.not(Matchers.equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, Matchers.equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    dep =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setMain(new PathSourcePath(filesystem, Paths.get("dep.exe")))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(resolver);
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
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
            .setCmd("run $(classpath //:dep)")
            .setOut("output");

    // Create an initial input-based rule key
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    JavaLibrary dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("source.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath("something", Paths.get("source.java"));
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    BuildRule rule = ruleBuilder.build(resolver);
    DefaultRuleKeyFactory defaultRuleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    RuleKey originalRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey originalInputRuleKey = inputBasedRuleKeyFactory.build(rule);

    // Change the dep's resource root, which will change its normal rule key, but since we're
    // keeping its output JAR the same, the input-based rule key for the consuming rule will stay
    // the same.  This is because the input-based rule key for the consuming rule only cares about
    // the contents of the output this rule produces.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
        .addSrc(Paths.get("source.java"))
        .setResourcesRoot(Paths.get("resource_root"))
        .build(resolver, filesystem);
    rule = ruleBuilder.build(resolver);
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    defaultRuleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    RuleKey unchangedRuleKey = defaultRuleKeyFactory.build(rule);
    RuleKey unchangedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(unchangedRuleKey, Matchers.not(Matchers.equalTo(originalRuleKey)));
    assertThat(unchangedInputBasedRuleKey, Matchers.equalTo(originalInputRuleKey));

    // Make a change to the dep's output, which *should* affect the input-based rule key.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .addSrc(Paths.get("source.java"))
            .build(resolver, filesystem);
    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getSourcePathToOutput()));
    rule = ruleBuilder.build(resolver);
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(
            0,
            new StackedFileHashCache(
                ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem))),
            pathResolver,
            ruleFinder);
    RuleKey changedInputBasedRuleKey = inputBasedRuleKeyFactory.build(rule);
    assertThat(changedInputBasedRuleKey, Matchers.not(Matchers.equalTo(originalInputRuleKey)));
  }
}

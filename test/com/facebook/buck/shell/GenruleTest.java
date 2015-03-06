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

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static com.facebook.buck.util.BuckConstant.GEN_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaBinaryRuleBuilder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeRuleKeyBuilderFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class GenruleTest {

  private static final String BASE_PATH = getAbsolutePathFor("/opt/local/fbandroid");

  private static final Function<Path, Path> ABSOLUTIFIER =
      new Function<Path, Path>() {
        @Override
        public Path apply(Path input) {
          return getAbsolutePathInBase(input.toString());
        }
      };

  private ProjectFilesystem fakeFilesystem;

  private RuleKey.Builder.RuleKeyPair generateRuleKey(
      RuleKeyBuilderFactory factory,
      SourcePathResolver resolver,
      AbstractBuildRule rule) {

    RuleKey.Builder builder = factory.newInstance(rule, resolver);
    rule.appendToRuleKey(builder);
    return builder.build();
  }

  @Before
  public void newFakeFilesystem() {
    fakeFilesystem = EasyMock.createNiceMock(ProjectFilesystem.class);
    EasyMock.expect(fakeFilesystem.getAbsolutifier())
        .andReturn(ABSOLUTIFIER)
        .times(0,  1);
    EasyMock.replay(fakeFilesystem);
  }

  @After
  public void verifyFakeFilesystem() {
    EasyMock.verify(fakeFilesystem);
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

    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    createSampleJavaBinaryRule(ruleResolver);

    // From the Python object, create a GenruleBuildRuleFactory to create a Genrule.Builder
    // that builds a Genrule from the Python object.
    BuildTargetParser parser = EasyMock.createNiceMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse(EasyMock.eq("//java/com/facebook/util:util"),
        EasyMock.anyObject(BuildTargetPatternParser.class)))
        .andStubReturn(BuildTargetFactory.newInstance("//java/com/facebook/util:util"));
    EasyMock.replay(parser);

    BuildTarget buildTarget =
        BuildTarget.builder("//src/com/facebook/katana", "katana_manifest").build();
    BuildRule genrule = GenruleBuilder
        .newGenruleBuilder(buildTarget)
        .setCmd("python convert_to_katana.py AndroidManifest.xml > $OUT")
        .setOut("AndroidManifest.xml")
        .setSrcs(
            ImmutableList.<SourcePath>of(
                new PathSourcePath(
                    fakeFilesystem,
                    Paths.get("src/com/facebook/katana/convert_to_katana.py")),
                new PathSourcePath(
                    fakeFilesystem,
                    Paths.get("src/com/facebook/katana/AndroidManifest.xml"))))
        .build(ruleResolver, fakeFilesystem);

    // Verify all of the observers of the Genrule.
    assertEquals(GEN_PATH.resolve("src/com/facebook/katana/AndroidManifest.xml"),
        genrule.getPathToOutputFile());
    assertEquals(
        getAbsolutePathInBase(GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml").toString(),
        ((Genrule) genrule).getAbsoluteOutputFilePath());
    BuildContext buildContext = null; // unused since there are no deps
    ImmutableList<Path> inputsToCompareToOutputs = ImmutableList.of(
        Paths.get("src/com/facebook/katana/convert_to_katana.py"),
        Paths.get("src/com/facebook/katana/AndroidManifest.xml"));
    assertEquals(
        inputsToCompareToOutputs,
        ((Genrule) genrule).getSrcs());

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = genrule.getBuildSteps(
        buildContext, new FakeBuildableContext());
    assertEquals(7, steps.size());

    Step firstStep = steps.get(0);
    assertTrue(firstStep instanceof RmStep);
    RmStep rmCommand = (RmStep) firstStep;
    ExecutionContext executionContext = newEmptyExecutionContext();
    assertEquals(
        "First command should delete the output file to be written by the genrule.",
        ImmutableList.of(
            "rm",
            "-f",
            GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml"),
        rmCommand.getShellCommand(executionContext));

    Step secondStep = steps.get(1);
    assertTrue(secondStep instanceof MkdirStep);
    MkdirStep mkdirCommand = (MkdirStep) secondStep;
    assertEquals(
        "Second command should make sure the output directory exists.",
        Paths.get(GEN_DIR + "/src/com/facebook/katana"),
        mkdirCommand.getPath(executionContext));

    Step mkTmpDir = steps.get(2);
    assertTrue(mkTmpDir instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep secondMkdirCommand = (MakeCleanDirectoryStep) mkTmpDir;
    Path pathToTmpDir = GEN_PATH.resolve("src/com/facebook/katana/katana_manifest__tmp");
    assertEquals(
        "Third command should create the temp directory to be written by the genrule.",
        pathToTmpDir,
        secondMkdirCommand.getPath());

    Step mkSrcDir = steps.get(3);
    assertTrue(mkSrcDir instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep thirdMkdirCommand = (MakeCleanDirectoryStep) mkTmpDir;
    Path pathToSrcDir = GEN_PATH.resolve("src/com/facebook/katana/katana_manifest__srcs");
    assertEquals(
        "Fourth command should create the temp source directory to be written by the genrule.",
        pathToTmpDir,
        thirdMkdirCommand.getPath());

    MkdirAndSymlinkFileStep linkSource1 = (MkdirAndSymlinkFileStep) steps.get(4);
    assertEquals(
        Paths.get("src/com/facebook/katana/convert_to_katana.py"),
        linkSource1.getSource());
    assertEquals(Paths.get(pathToSrcDir + "/convert_to_katana.py"), linkSource1.getTarget());

    MkdirAndSymlinkFileStep linkSource2 = (MkdirAndSymlinkFileStep) steps.get(5);
    assertEquals(Paths.get("src/com/facebook/katana/AndroidManifest.xml"), linkSource2.getSource());
    assertEquals(Paths.get(pathToSrcDir + "/AndroidManifest.xml"), linkSource2.getTarget());

    Step sixthStep = steps.get(6);
    assertTrue(sixthStep instanceof ShellStep);
    ShellStep genruleCommand = (ShellStep) sixthStep;
    assertEquals("genrule", genruleCommand.getShortName());
    assertEquals(ImmutableMap.<String, String>builder()
        .put("OUT",
            getAbsolutePathInBase(
                GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml").toString())
        .build(),
        genruleCommand.getEnvironmentVariables(executionContext));
    assertEquals(
        ImmutableList.of(
            "/bin/bash",
            "-e",
            "-c",
            "python convert_to_katana.py AndroidManifest.xml > $OUT"),
        genruleCommand.getShellCommand(executionContext));
  }

  @Test
  public void testDepsEnvironmentVariableIsComplete() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget depTarget = BuildTarget.builder("//foo", "bar").build();
    BuildRule dep = new FakeBuildRule(
        JavaLibraryDescription.TYPE,
        depTarget,
        new SourcePathResolver(new BuildRuleResolver())) {
      @Override
      public Path getPathToOutputFile() {
        return Paths.get("buck-out/gen/foo/bar.jar");
      }
    };
    resolver.addToIndex(dep);

    BuildRule genrule = GenruleBuilder
        .newGenruleBuilder(
            BuildTarget.builder(
                "//foo",
                "baz").build())
        .setBash("cat $DEPS > $OUT")
        .setOut("deps.txt")
        .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
        .build(resolver, fakeFilesystem);

    AbstractGenruleStep genruleStep = ((Genrule) genrule).createGenruleStep();
    ExecutionContext context = newEmptyExecutionContext(Platform.LINUX);
    ImmutableMap<String, String> environmentVariables =
        genruleStep.getEnvironmentVariables(context);
    assertEquals(
        "Make sure that the use of $DEPS pulls in $GEN_DIR, as well.",
        ImmutableMap.of(
            "DEPS", "$GEN_DIR/foo/bar.jar",
            "GEN_DIR", getAbsolutePathInBase("buck-out/gen").toString(),
            "OUT", getAbsolutePathInBase("buck-out/gen/foo/deps.txt").toString()),
        environmentVariables);

    // Ensure that $GEN_DIR is declared before $DEPS.
    List<String> keysInOrder = ImmutableList.copyOf(environmentVariables.keySet());
    assertEquals("GEN_DIR", keysInOrder.get(1));
    assertEquals("DEPS", keysInOrder.get(2));
  }

  private ExecutionContext newEmptyExecutionContext(Platform platform) {
    return TestExecutionContext
        .newBuilder()
        .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
        .setPlatform(platform)
        .build();
  }

  private ExecutionContext newEmptyExecutionContext() {
    return newEmptyExecutionContext(Platform.detect());
  }

  @Test
  public void ensureFilesInSubdirectoriesAreKeptInSubDirectories() throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:example");
    BuildRule rule = GenruleBuilder
        .newGenruleBuilder(target)
        .setBash("ignored")
        .setSrcs(
            ImmutableList.<SourcePath>of(
                new PathSourcePath(projectFilesystem, Paths.get("in-dir.txt")),
                new PathSourcePath(projectFilesystem, Paths.get("foo/bar.html")),
                new PathSourcePath(projectFilesystem, Paths.get("other/place.txt"))))
        .setOut("example-file")
        .build(resolver);

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    ((Genrule) rule).addSymlinkCommands(builder);
    ImmutableList<Step> commands = builder.build();

    String baseTmpPath = GEN_DIR + "/example__srcs/";

    assertEquals(3, commands.size());
    MkdirAndSymlinkFileStep linkCmd = (MkdirAndSymlinkFileStep) commands.get(0);
    assertEquals(Paths.get("in-dir.txt"), linkCmd.getSource());
    assertEquals(Paths.get(baseTmpPath + "in-dir.txt"), linkCmd.getTarget());

    linkCmd = (MkdirAndSymlinkFileStep) commands.get(1);
    assertEquals(Paths.get("foo/bar.html"), linkCmd.getSource());
    assertEquals(Paths.get(baseTmpPath + "foo/bar.html"), linkCmd.getTarget());

    linkCmd = (MkdirAndSymlinkFileStep) commands.get(2);
    assertEquals(Paths.get("other/place.txt"), linkCmd.getSource());
    assertEquals(Paths.get(baseTmpPath + "other/place.txt"), linkCmd.getTarget());
  }

  private BuildRule createSampleJavaBinaryRule(BuildRuleResolver ruleResolver) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildRule javaLibrary = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
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
  public void testShouldIncludeDxInEnvironmentIfPresent() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    AndroidPlatformTarget android = EasyMock.createNiceMock(AndroidPlatformTarget.class);
    EasyMock.expect(android.getDxExecutable()).andStubReturn(Paths.get("."));
    EasyMock.expect(android.getZipalignExecutable()).andStubReturn(Paths.get("zipalign"));
    EasyMock.replay(android);

    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(target)
        .setBash("true")
        .setOut("/dev/null")
        .build(resolver);

    ExecutionContext context = TestExecutionContext.newBuilder()
        .setAndroidPlatformTargetSupplier(Suppliers.ofInstance(android))
        .build();

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    genrule.addEnvironmentVariables(context, builder);
    ImmutableMap<String, String> env = builder.build();

    assertEquals(Paths.get(".").toString(), env.get("DX"));
    assertEquals(Paths.get("zipalign").toString(), env.get("ZIPALIGN"));

    EasyMock.verify(android);
  }

  @Test
  public void shouldPreventTheParentBuckdBeingUsedIfARecursiveBuckCallIsMade() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    Genrule genrule = (Genrule) GenruleBuilder.newGenruleBuilder(target)
        .setBash("true")
        .setOut("/dev/null")
        .build(resolver);

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    genrule.addEnvironmentVariables(TestExecutionContext.newInstance(), builder);

    assertEquals("1", builder.build().get("NO_BUCKD"));
  }

  @Test
  public void testGetShellCommand() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    String bash = "rm -rf /usr";
    String cmdExe = "rmdir /s /q C:\\Windows";
    String cmd = "echo \"Hello\"";
    ExecutionContext linuxExecutionContext = newEmptyExecutionContext(Platform.LINUX);
    ExecutionContext windowsExecutionContext = newEmptyExecutionContext(Platform.WINDOWS);

    // Test platform-specific
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule1"))
        .setBash(bash)
        .setCmdExe(cmdExe)
        .setOut("out.txt")
        .build(resolver);

    ImmutableList<String> command = genrule
        .createGenruleStep()
        .getShellCommand(linuxExecutionContext);
    assertEquals(ImmutableList.of("/bin/bash", "-e", "-c", bash), command);

    command = genrule.createGenruleStep().getShellCommand(windowsExecutionContext);
    assertEquals(ImmutableList.of("cmd.exe", "/c", cmdExe), command);

    // Test fallback
    genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule2"))
        .setCmd(cmd)
        .setOut("out.txt")
        .build(resolver);
    command = genrule.createGenruleStep().getShellCommand(linuxExecutionContext);
    assertEquals(ImmutableList.of("/bin/bash", "-e", "-c", cmd), command);

    command = genrule.createGenruleStep().getShellCommand(windowsExecutionContext);
    assertEquals(ImmutableList.of("cmd.exe", "/c", cmd), command);

    // Test command absent
    genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//example:genrule3"))
        .setOut("out.txt")
        .build(resolver);
    try {
      genrule.createGenruleStep().getShellCommand(linuxExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(String.format("You must specify either bash or cmd for genrule %s.",
          genrule.getBuildTarget()), e.getHumanReadableErrorMessage());
    }

    try {
      genrule.createGenruleStep().getShellCommand(windowsExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(String.format("You must specify either cmd_exe or cmd for genrule %s.",
          genrule.getBuildTarget()), e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void testGetOutputNameMethod() {
    {
      String name = "out.txt";
      Genrule genrule = (Genrule) GenruleBuilder
          .newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
          .setOut(name)
          .build(new BuildRuleResolver());
      assertEquals(name, genrule.getOutputName());
    }
    {
      String name = "out/file.txt";
      Genrule genrule = (Genrule) GenruleBuilder
          .newGenruleBuilder(BuildTargetFactory.newInstance("//:test"))
          .setOut(name)
          .build(new BuildRuleResolver());
      assertEquals(name, genrule.getOutputName());
    }
  }

  @Test
  public void thatChangingOutChangesRuleKey() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    RuleKeyBuilderFactory ruleKeyBuilderFactory = new FakeRuleKeyBuilderFactory();

    // Get a rule key for two genrules using two different output names, but are otherwise the
    // same.
    RuleKey.Builder.RuleKeyPair key1 = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        (Genrule) GenruleBuilder
            .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule1"))
            .setOut("foo")
            .build(resolver));
    RuleKey.Builder.RuleKeyPair key2 = generateRuleKey(
        ruleKeyBuilderFactory,
        pathResolver,
        (Genrule) GenruleBuilder
            .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule2"))
            .setOut("bar")
            .build(resolver));

    // Verify that just the difference in output name is enough to make the rule key different.
    assertNotEquals(key1.getTotalRuleKey(), key2.getTotalRuleKey());
  }

  private static String getAbsolutePathFor(String path) {
    return new File(path).getAbsolutePath();
  }

  private static Path getAbsolutePathInBase(String path) {
    return Paths.get(BASE_PATH, path);
  }
}

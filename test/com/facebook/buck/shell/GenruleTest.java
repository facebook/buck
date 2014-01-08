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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.JavaBinaryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.NonCheckingBuildRuleFactoryParams;
import com.facebook.buck.shell.Genrule.Builder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    Map<String, ?> instance = ImmutableMap.of(
        "name", "katana_manifest",
        "srcs", ImmutableList.<String>of("convert_to_katana.py", "AndroidManifest.xml"),
        "cmd", "python convert_to_katana.py AndroidManifest.xml > $OUT",
        "out", "AndroidManifest.xml",
        "deps", ImmutableList.<String>of("//java/com/facebook/util:util"));

    // From the Python object, create a GenruleBuildRuleFactory to create a Genrule.Builder
    // that builds a Genrule from the Python object.
    BuildTargetParser parser = EasyMock.createNiceMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse(EasyMock.eq("//java/com/facebook/util:util"),
        EasyMock.anyObject(ParseContext.class)))
        .andStubReturn(BuildTargetFactory.newInstance("//java/com/facebook/util:util"));
    EasyMock.replay(parser);

    BuildTarget buildTarget = new BuildTarget("//src/com/facebook/katana", "katana_manifest");
    BuildRuleFactoryParams params = NonCheckingBuildRuleFactoryParams.
        createNonCheckingBuildRuleFactoryParams(
            instance,
            parser,
            buildTarget);
    GenruleBuildRuleFactory factory = new GenruleBuildRuleFactory();
    Builder builder = factory.newInstance(params);
    builder.setRelativeToAbsolutePathFunctionForTesting(ABSOLUTIFIER);
    Genrule genrule = ruleResolver.buildAndAddToIndex(builder);

    // Verify all of the observers of the Genrule.
    assertEquals(BuildRuleType.GENRULE, genrule.getType());
    assertEquals(GEN_PATH.resolve("src/com/facebook/katana/AndroidManifest.xml"),
        genrule.getPathToOutputFile());
    assertEquals(
        getAbsolutePathInBase(GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml").toString(),
        genrule.getAbsoluteOutputFilePath());
    BuildContext buildContext = null; // unused since there are no deps
    ImmutableSortedSet<Path> inputsToCompareToOutputs = ImmutableSortedSet.of(
        Paths.get("src/com/facebook/katana/convert_to_katana.py"),
        Paths.get("src/com/facebook/katana/AndroidManifest.xml"));
    assertEquals(inputsToCompareToOutputs,
        genrule.getInputsToCompareToOutput());

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = genrule.getBuildSteps(buildContext, new FakeBuildableContext());
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
    MakeCleanDirectoryStep secondMkdirCommand = (MakeCleanDirectoryStep)mkTmpDir;
    String pathToTmpDir = GEN_DIR + "/src/com/facebook/katana/katana_manifest__tmp";
    assertEquals(
        "Third command should create the temp directory to be written by the genrule.",
        pathToTmpDir,
        secondMkdirCommand.getPath());

    Step mkSrcDir = steps.get(3);
    assertTrue(mkSrcDir instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep thirdMkdirCommand = (MakeCleanDirectoryStep)mkTmpDir;
    String pathToSrcDir = GEN_DIR + "/src/com/facebook/katana/katana_manifest__srcs";
    assertEquals(
        "Fourth command should create the temp source directory to be written by the genrule.",
        pathToTmpDir,
        thirdMkdirCommand.getPath());

    MkdirAndSymlinkFileStep linkSource1 = (MkdirAndSymlinkFileStep) steps.get(4);
    assertEquals(Paths.get("src/com/facebook/katana/convert_to_katana.py"), linkSource1.getSource());
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
        ImmutableList.of("/bin/bash", "-e", "-c", "python convert_to_katana.py AndroidManifest.xml > $OUT"),
        genruleCommand.getShellCommand(executionContext));
  }

  @Test
  public void testDepsEnvironmentVariableIsComplete() {
    BuildTarget depTarget = new BuildTarget("//foo", "bar");
    BuildRule dep = new FakeBuildRule(BuildRuleType.JAVA_LIBRARY, depTarget) {
      @Override
      public Path getPathToOutputFile() {
        return Paths.get("buck-out/gen/foo/bar.jar");
      }
    };
    BuildRuleResolver ruleResolver = new BuildRuleResolver(ImmutableMap.of(depTarget, dep));

    AbstractBuildRuleBuilderParams params = new FakeAbstractBuildRuleBuilderParams();
    Builder builder = Genrule.newGenruleBuilder(params);
    builder.setBuildTarget(new BuildTarget("//foo", "baz"));
    builder.setBash(Optional.of("cat $DEPS > $OUT"));
    builder.setOut("deps.txt");
    builder.addDep(depTarget);

    Genrule genrule = builder.build(ruleResolver);
    AbstractGenruleStep genruleStep = genrule.createGenruleStep();
    ExecutionContext context = newEmptyExecutionContext(Platform.LINUX);
    Map<String, String> environmentVariables = genruleStep.getEnvironmentVariables(context);
    assertEquals(
        "Make sure that the use of $DEPS pulls in $GEN_DIR, as well.",
        ImmutableMap.of(
            "DEPS", "$GEN_DIR/foo/bar.jar",
            "GEN_DIR", "buck-out/gen",
            "OUT", "buck-out/gen/foo/deps.txt"),
        environmentVariables);

    // Ensure that $GEN_DIR is declared before $DEPS.
    List<String> keysInOrder = ImmutableList.copyOf(environmentVariables.keySet());
    assertEquals("GEN_DIR", keysInOrder.get(1));
    assertEquals("DEPS", keysInOrder.get(2));
  }

  private ExecutionContext newEmptyExecutionContext(Platform platform) {
    return ExecutionContext.builder()
        .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
        .setProjectFilesystem(new ProjectFilesystem(new File(".")) {
          @Override
          public Path resolve(Path path) {
            return path;
          }
        })
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(platform)
        .build();
  }

  private ExecutionContext newEmptyExecutionContext() {
    return newEmptyExecutionContext(Platform.detect());
  }

  @Test
  public void testBuildTargetPattern() {
    Pattern buildTargetPattern = AbstractGenruleStep.BUILD_TARGET_PATTERN;
    assertTrue(buildTargetPattern.matcher("$(exe //first-party/orca/orcaapp:manifest)").find());
    assertFalse(buildTargetPattern.matcher("\\$(exe //first-party/orca/orcaapp:manifest)").find());
    assertFalse(buildTargetPattern.matcher("$(exe first-party/orca/orcaapp:manifest)").find());
    assertTrue(buildTargetPattern.matcher("$(exe :manifest)").find());
    assertTrue(buildTargetPattern.matcher("$(exe //:manifest)").find());
    assertTrue(buildTargetPattern.matcher(
        "$(exe :some_manifest) inhouse $SRCDIR/AndroidManifest.xml $OUT").find());
  }

  @Test
  public void testLocationBuildTargetPattern() {
    Pattern buildTargetPattern = AbstractGenruleStep.BUILD_TARGET_PATTERN;
    assertTrue(buildTargetPattern.matcher("$(location //first-party/orca/orcaapp:manifest)").find());
    assertFalse(buildTargetPattern.matcher("\\$(location //first-party/orca/orcaapp:manifest)").find());
    assertFalse(buildTargetPattern.matcher("$(location first-party/orca/orcaapp:manifest)").find());
    assertTrue(buildTargetPattern.matcher("$(location :manifest)").find());
    assertTrue(buildTargetPattern.matcher("$(location   :manifest)").find());
    assertTrue(buildTargetPattern.matcher("$(location\t:manifest)").find());
    assertTrue(buildTargetPattern.matcher("$(location //:manifest)").find());
    assertTrue(buildTargetPattern.matcher(
        "$(location :some_manifest) inhouse $SRCDIR/AndroidManifest.xml $OUT").find());
  }

  @Test
  public void testReplaceBinaryBuildRuleRefsInCmd() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(ruleResolver);

    String originalCmd = "$(exe //java/com/facebook/util:ManifestGenerator) $OUT";
    String contextBasePath = "java/com/facebook/util";
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);

    Genrule rule = createGenrule(ruleResolver, originalCmd, contextBasePath, deps);
    AbstractGenruleStep genruleStep = rule.createGenruleStep();

    // Interpolate the build target in the genrule cmd string.
    String transformedString = genruleStep.replaceMatches(fakeFilesystem, originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath = getAbsolutePathInBase(
        GEN_DIR + "/java/com/facebook/util/ManifestGenerator.jar");

    String expectedCmd = String.format(
        "java -jar %s $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void testReplaceRelativeBinaryBuildRuleRefsInCmd() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(ruleResolver);

    String originalCmd = "$(exe :ManifestGenerator) $OUT";
    String contextBasePath = "java/com/facebook/util";
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);

    Genrule rule = createGenrule(ruleResolver, originalCmd, contextBasePath, deps);
    AbstractGenruleStep genruleStep = rule.createGenruleStep();

    // Interpolate the build target in the genrule cmd string.
    String transformedString = genruleStep.replaceMatches(fakeFilesystem, originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath = getAbsolutePathInBase(
        GEN_DIR + "/java/com/facebook/util/ManifestGenerator.jar");
    String expectedCmd = String.format(
        "java -jar %s $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void replaceLocationOfFullyQualifiedBuildTarget() {
    ProjectFilesystem filesystem = EasyMock.createNiceMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.getAbsolutifier()).andStubReturn(ABSOLUTIFIER);
    EasyMock.replay(filesystem);

    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(ruleResolver);

    String originalCmd = String.format("$(location :%s) $(location %s) $OUT",
        javaBinary.getBuildTarget().getShortName(),
        javaBinary.getBuildTarget().getFullyQualifiedName());

    String contextBasePath = javaBinary.getBuildTarget().getBasePath();
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);

    Genrule rule = createGenrule(ruleResolver, originalCmd, contextBasePath, deps);
    AbstractGenruleStep genruleStep = rule.createGenruleStep();

    // Interpolate the build target in the genrule cmd string.
    String transformedString = genruleStep.replaceMatches(filesystem, originalCmd);

    // Verify that the correct cmd was created.
    Path pathToOutput = getAbsolutePathInBase(
        GEN_DIR + "/java/com/facebook/util/ManifestGenerator.jar");
    String expectedCmd = String.format("%s %s $OUT", pathToOutput, pathToOutput);
    assertEquals(expectedCmd, transformedString);
    EasyMock.verify(filesystem);
  }


  @Test
  public void testDepsGenrule() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(ruleResolver);

    // Interpolate the build target in the genrule cmd string.
    String originalCmd = "$(exe :ManifestGenerator) $OUT";
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);
    String contextBasePath = "java/com/facebook/util";

    Genrule rule = createGenrule(ruleResolver, originalCmd, contextBasePath, deps);
    AbstractGenruleStep genruleStep = rule.createGenruleStep();

    String transformedString = genruleStep.replaceMatches(fakeFilesystem, originalCmd);

    // Verify that the correct cmd was created.
    Path expectedClasspath = getAbsolutePathInBase(
        GEN_DIR + "/java/com/facebook/util/ManifestGenerator.jar");
    String expectedCmd = String.format(
        "java -jar %s $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void ensureFilesInSubdirectoriesAreKeptInSubDirectories() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget target = BuildTargetFactory.newInstance("//:example");
    Genrule rule = ruleResolver.buildAndAddToIndex(
        Genrule.newGenruleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setRelativeToAbsolutePathFunctionForTesting(ABSOLUTIFIER)
        .setBuildTarget(target)
        .setBash(Optional.of("ignored"))
        .addSrc(Paths.get("in-dir.txt"))
        .addSrc(Paths.get("foo/bar.html"))
        .addSrc(Paths.get("other/place.txt"))
        .setOut("example-file"));

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    rule.addSymlinkCommands(builder);
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

  private JavaBinaryRule createSampleJavaBinaryRule(BuildRuleResolver ruleResolver) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    JavaLibraryRule javaLibrary = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .addSrc(Paths.get("java/com/facebook/util/ManifestGenerator.java")));

    JavaBinaryRule javaBinary = ruleResolver.buildAndAddToIndex(
        JavaBinaryRule.newJavaBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator"))
        .setMainClass("com.facebook.util.ManifestGenerator")
        .addDep(javaLibrary.getBuildTarget()));

    return javaBinary;
  }

  private Genrule createGenrule(BuildRuleResolver ruleResolver,
                                String originalCmd,
                                String contextBasePath,
                                Set<? extends BuildRule> deps) {
    BuildTarget target = BuildTargetFactory.newInstance(
        String.format("//%s:genrule", contextBasePath));

    Builder ruleBuilder = Genrule.newGenruleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setRelativeToAbsolutePathFunctionForTesting(ABSOLUTIFIER)
        .setBuildTarget(target)
        .setBash(Optional.of(originalCmd))
        .setOut("example-file");

    for (BuildRule dep : deps) {
      ruleBuilder.addDep(dep.getBuildTarget());
    }

    return ruleResolver.buildAndAddToIndex(ruleBuilder);
  }

  @Test
  public void testShouldIncludeDxInEnvironmentIfPresent() {
    File fakeDx = new File(".");  // We do no checks on whether dx is executable, but it must exist
    AndroidPlatformTarget android = EasyMock.createNiceMock(AndroidPlatformTarget.class);
    EasyMock.expect(android.getDxExecutable()).andStubReturn(fakeDx);
    EasyMock.replay(android);

    Genrule rule = Genrule.newGenruleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//example:genrule"))
        .setBash(Optional.of("true"))
        .setOut("/dev/null")
        .build(new BuildRuleResolver());

    ExecutionContext context = ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(new ProjectFilesystem(new File(".")))
        .setAndroidPlatformTarget(Optional.of(android))
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.detect())
        .build();

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    rule.addEnvironmentVariables(context, builder);
    ImmutableMap<String, String> env = builder.build();

    assertEquals(fakeDx.getAbsolutePath(), env.get("DX"));

    EasyMock.verify(android);
  }

  @Test
  public void testGetShellCommand() {
    String bash = "rm -rf /usr";
    String cmdExe = "rmdir /s /q C:\\Windows";
    String cmd = "echo \"Hello\"";
    String genruleName = "//example:genrule";
    ExecutionContext linuxExecutionContext = newEmptyExecutionContext(Platform.LINUX);
    ExecutionContext windowsExecutionContext = newEmptyExecutionContext(Platform.WINDOWS);

    // Test platform-specific
    Genrule rule = Genrule.newGenruleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance(genruleName))
        .setBash(Optional.of(bash))
        .setCmdExe(Optional.of(cmdExe))
        .setOut("out.txt")
        .build(new BuildRuleResolver());

    ImmutableList<String> command = rule.createGenruleStep().getShellCommand(linuxExecutionContext);
    assertEquals(ImmutableList.of("/bin/bash", "-e", "-c", bash), command);

    command = rule.createGenruleStep().getShellCommand(windowsExecutionContext);
    assertEquals(ImmutableList.of("cmd.exe", "/c", cmdExe), command);

    // Test fallback
    rule = Genrule.newGenruleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//example:genrule"))
        .setCmd(Optional.of(cmd))
        .setOut("out.txt")
        .build(new BuildRuleResolver());
    command = rule.createGenruleStep().getShellCommand(linuxExecutionContext);
    assertEquals(ImmutableList.of("/bin/bash", "-e", "-c", cmd), command);

    command = rule.createGenruleStep().getShellCommand(windowsExecutionContext);
    assertEquals(ImmutableList.of("cmd.exe", "/c", cmd), command);

    // Test command absent
    rule = Genrule.newGenruleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//example:genrule"))
        .setOut("out.txt")
        .build(new BuildRuleResolver());
    try {
      rule.createGenruleStep().getShellCommand(linuxExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(String.format("You must specify either bash or cmd for genrule %s.",
          genruleName), e.getHumanReadableErrorMessage());
    }

    try {
      rule.createGenruleStep().getShellCommand(windowsExecutionContext);
    } catch (HumanReadableException e) {
      assertEquals(String.format("You must specify either cmd_exe or cmd for genrule %s.",
          genruleName), e.getHumanReadableErrorMessage());
    }
  }

  private static String getAbsolutePathFor(String path) {
    return new File(path).getAbsolutePath();
  }

  private static Path getAbsolutePathInBase(String path) {
    return Paths.get(BASE_PATH, path);
  }
}

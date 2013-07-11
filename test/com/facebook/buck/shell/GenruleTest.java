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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.JavaBinaryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.NonCheckingBuildRuleFactoryParams;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.shell.Genrule.Builder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Function;
import com.google.common.base.Functions;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class GenruleTest {

  private static final Function<String, String> relativeToAbsolutePathFunction =
      new Function<String, String>() {
        @Override
        public String apply(String path) {
          return String.format("/opt/local/fbandroid/%s", path);
        }
      };

  private ProjectFilesystem fakeFilesystem;

  @Before
  public void newFakeFilesystem() {
    fakeFilesystem = EasyMock.createNiceMock(ProjectFilesystem.class);
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

    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//src/com/facebook/katana", "katana_manifest");
    BuildRuleFactoryParams params = NonCheckingBuildRuleFactoryParams.
        createNonCheckingBuildRuleFactoryParams(
            instance,
            parser,
            buildTarget);
    GenruleBuildRuleFactory factory = new GenruleBuildRuleFactory();
    Builder builder = factory.newInstance(params);
    builder.setRelativeToAbsolutePathFunction(relativeToAbsolutePathFunction);
    Genrule genrule = ruleResolver.buildAndAddToIndex(builder);

    // Verify all of the observers of the Genrule.
    assertEquals(BuildRuleType.GENRULE, genrule.getType());
    assertEquals(GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml",
        genrule.getPathToOutputFile());
    assertEquals("/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml",
        genrule.getAbsoluteOutputFilePath());
    BuildContext buildContext = null; // unused since there are no deps
    ImmutableSortedSet<String> inputsToCompareToOutputs = ImmutableSortedSet.of(
        "src/com/facebook/katana/convert_to_katana.py",
        "src/com/facebook/katana/AndroidManifest.xml");
    assertEquals(inputsToCompareToOutputs,
        genrule.getInputsToCompareToOutput());

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = genrule.buildInternal(buildContext);
    assertEquals(7, steps.size());

    Step firstStep = steps.get(0);
    assertTrue(firstStep instanceof ShellStep);
    ShellStep rmCommand = (ShellStep) firstStep;
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
        ImmutableList.of("mkdir", "-p", GEN_DIR + "/src/com/facebook/katana/"),
        mkdirCommand.getShellCommand(executionContext));

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
    assertEquals("src/com/facebook/katana/convert_to_katana.py", linkSource1.getSource());
    assertEquals(pathToSrcDir + "/convert_to_katana.py", linkSource1.getTarget());

    MkdirAndSymlinkFileStep linkSource2 = (MkdirAndSymlinkFileStep) steps.get(5);
    assertEquals("src/com/facebook/katana/AndroidManifest.xml", linkSource2.getSource());
    assertEquals(pathToSrcDir + "/AndroidManifest.xml", linkSource2.getTarget());

    Step sixthStep = steps.get(6);
    assertTrue(sixthStep instanceof ShellStep);
    ShellStep genruleCommand = (ShellStep) sixthStep;
    assertEquals("genrule: python convert_to_katana.py AndroidManifest.xml > $OUT",
        genruleCommand.getShortName(executionContext));
    assertEquals(ImmutableMap.<String, String>builder()
        .put("SRCS", "/opt/local/fbandroid/src/com/facebook/katana/convert_to_katana.py " +
            "/opt/local/fbandroid/src/com/facebook/katana/AndroidManifest.xml")
        .put("OUT", "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml")
        .put("DEPS",
            "/opt/local/fbandroid/" + GEN_DIR + "/java/com/facebook/util/lib__util__output/util.jar")
        .put("TMP", "/opt/local/fbandroid/" + pathToTmpDir)
        .put("SRCDIR", "/opt/local/fbandroid/" + pathToSrcDir)
        .build(),
        genruleCommand.getEnvironmentVariables(executionContext));
    assertEquals(
        ImmutableList.of("/bin/bash", "-c", "python convert_to_katana.py AndroidManifest.xml > $OUT"),
        genruleCommand.getShellCommand(executionContext));
  }

  private ExecutionContext newEmptyExecutionContext() {
    return ExecutionContext.builder()
        .setConsole(new Console(Verbosity.SILENT, System.out, System.err, Ansi.withoutTty()))
        .setProjectFilesystem(new ProjectFilesystem(new File(".")) {
          @Override
          public Function<String, String> getPathRelativizer() {
            return Functions.identity();
          }
        })
        .setEventBus(new BuckEventBus())
        .build();
  }

  @Test
  public void testBuildTargetPattern() {
    Pattern buildTargetPattern = Genrule.BUILD_TARGET_PATTERN;
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
    Pattern buildTargetPattern = Genrule.BUILD_TARGET_PATTERN;
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

    // Interpolate the build target in the genrule cmd string.
    String transformedString = rule.replaceMatches(fakeFilesystem, originalCmd);

    // This creates an absolute path that ends with "/.", so drop the ".".
    String basePathWithTrailingDot = new File(".").getAbsolutePath();
    String basePath = basePathWithTrailingDot.substring(0, basePathWithTrailingDot.length() - 1);

    // Verify that the correct cmd was created.
    String expectedClasspath =
        basePath + GEN_DIR + "/java/com/facebook/util/lib__util__output/util.jar";
    String expectedCmd = String.format(
        "java -classpath %s com.facebook.util.ManifestGenerator $OUT",
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

    // Interpolate the build target in the genrule cmd string.
    String transformedString = rule.replaceMatches(fakeFilesystem, originalCmd);

    // This creates an absolute path that ends with "/.", so drop the ".".
    String basePathWithTrailingDot = new File(".").getAbsolutePath();
    String basePath = basePathWithTrailingDot.substring(0, basePathWithTrailingDot.length() - 1);

    // Verify that the correct cmd was created.
    String expectedClasspath =
        basePath + GEN_DIR + "/java/com/facebook/util/lib__util__output/util.jar";
    String expectedCmd = String.format(
        "java -classpath %s com.facebook.util.ManifestGenerator $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void replaceLocationOfFullyQualifiedBuildTarget() {
    ProjectFilesystem filesystem = EasyMock.createNiceMock(ProjectFilesystem.class);
    EasyMock.expect(filesystem.getPathRelativizer()).andStubReturn(relativeToAbsolutePathFunction);
    EasyMock.replay(filesystem);

    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(ruleResolver);

    String originalCmd = String.format("$(location :%s) $(location %s) $OUT",
        javaBinary.getBuildTarget().getShortName(),
        javaBinary.getBuildTarget().getFullyQualifiedName());

    String contextBasePath = javaBinary.getBuildTarget().getBasePath();
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);

    Genrule rule = createGenrule(ruleResolver, originalCmd, contextBasePath, deps);

    // Interpolate the build target in the genrule cmd string.
    String transformedString = rule.replaceMatches(filesystem, originalCmd);

    // Verify that the correct cmd was created.
    String pathToOutput = String.format(
        "/opt/local/fbandroid/%s/java/com/facebook/util/ManifestGenerator.jar " +
        "/opt/local/fbandroid/%s/java/com/facebook/util/ManifestGenerator.jar",
        GEN_DIR,
        GEN_DIR);
    String expectedCmd = String.format("%s $OUT", pathToOutput);
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

    String transformedString = rule.replaceMatches(fakeFilesystem, originalCmd);

    // This creates an absolute path that ends with "/.", so drop the ".".
    String basePathWithTrailingDot = new File(".").getAbsolutePath();
    String basePath = basePathWithTrailingDot.substring(0, basePathWithTrailingDot.length() - 1);

    // Verify that the correct cmd was created.
    String expectedClasspath =
        basePath + GEN_DIR + "/java/com/facebook/util/lib__util__output/util.jar";
    String expectedCmd = String.format(
        "java -classpath %s com.facebook.util.ManifestGenerator $OUT",
        expectedClasspath);
    assertEquals(expectedCmd, transformedString);
  }

  @Test
  public void ensureFilesInSubdirectoriesAreKeptInSubDirectories() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget target = BuildTargetFactory.newInstance("//:example");
    Genrule rule = ruleResolver.buildAndAddToIndex(
        Genrule.newGenruleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setRelativeToAbsolutePathFunction(relativeToAbsolutePathFunction)
        .setBuildTarget(target)
        .setCmd("ignored")
        .addSrc("in-dir.txt")
        .addSrc("foo/bar.html")
        .addSrc("other/place.txt")
        .setOut("example-file"));

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    rule.addSymlinkCommands(builder);
    ImmutableList<Step> commands = builder.build();

    String baseTmpPath = GEN_DIR + "/example__srcs/";

    assertEquals(3, commands.size());
    MkdirAndSymlinkFileStep linkCmd = (MkdirAndSymlinkFileStep) commands.get(0);
    assertEquals("in-dir.txt", linkCmd.getSource());
    assertEquals(baseTmpPath + "in-dir.txt", linkCmd.getTarget());

    linkCmd = (MkdirAndSymlinkFileStep) commands.get(1);
    assertEquals("foo/bar.html", linkCmd.getSource());
    assertEquals(baseTmpPath + "foo/bar.html", linkCmd.getTarget());

    linkCmd = (MkdirAndSymlinkFileStep) commands.get(2);
    assertEquals("other/place.txt", linkCmd.getSource());
    assertEquals(baseTmpPath + "other/place.txt", linkCmd.getTarget());
  }

  private JavaBinaryRule createSampleJavaBinaryRule(BuildRuleResolver ruleResolver) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    JavaLibraryRule javaLibrary = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .addSrc("java/com/facebook/util/ManifestGenerator.java"));

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
        .setRelativeToAbsolutePathFunction(relativeToAbsolutePathFunction)
        .setBuildTarget(target)
        .setCmd(originalCmd)
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
        .setCmd("true")
        .setOut("/dev/null")
        .build(new BuildRuleResolver());

    ExecutionContext context = ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(new ProjectFilesystem(new File(".")))
        .setAndroidPlatformTarget(Optional.of(android))
        .setEventBus(new BuckEventBus())
        .build();

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    rule.addEnvironmentVariables(context, builder);
    ImmutableMap<String, String> env = builder.build();

    assertEquals(fakeDx.getAbsolutePath(), env.get("DX"));

    EasyMock.verify(android);
  }
}

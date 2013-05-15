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
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.shell.Genrule.Builder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class GenruleTest {

  private static final ArtifactCache artifactCache = new NoopArtifactCache();

  private static final Function<String, String> relativeToAbsolutePathFunction =
      new Function<String, String>() {
        @Override
        public String apply(String path) {
          return String.format("/opt/local/fbandroid/%s", path);
        }
      };

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
     *   cmd = 'python $SRC_0 $SRC_1 > $OUT',
     *   out = 'AndroidManifest.xml',
     * )
     */

    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    createSampleJavaBinaryRule(buildRuleIndex);

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
    Builder builder = (Builder)factory.newInstance(params);
    builder.setRelativeToAbsolutePathFunction(relativeToAbsolutePathFunction);
    Genrule genrule = builder.build(buildRuleIndex);

    // Verify all of the observers of the Genrule.
    assertEquals(BuildRuleType.GENRULE, genrule.getType());
    assertEquals("/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml",
        genrule.getOutputFilePath());
    BuildContext buildContext = null; // unused since there are no deps
    ImmutableSortedSet<String> inputsToCompareToOutputs = ImmutableSortedSet.of(
        "src/com/facebook/katana/convert_to_katana.py",
        "src/com/facebook/katana/AndroidManifest.xml");
    assertEquals(inputsToCompareToOutputs,
        genrule.getInputsToCompareToOutput(buildContext));

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = genrule.buildInternal(buildContext);
    assertEquals(7, steps.size());

    Step firstStep = steps.get(0);
    assertTrue(firstStep instanceof ShellStep);
    ShellStep rmCommand = (ShellStep) firstStep;
    ExecutionContext executionContext = null;
    assertEquals(
        "First command should delete the output file to be written by the genrule.",
        ImmutableList.of(
            "rm",
            "-f",
            "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/katana/AndroidManifest.xml"),
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
    String tempDirPath =
        "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/katana/katana_manifest__tmp";
    assertEquals(
        "Third command should delete the temp directory to be written by the genrule.",
        tempDirPath,
        secondMkdirCommand.getPath());

    Step mkSrcDir = steps.get(3);
    assertTrue(mkSrcDir instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep thirdMkdirCommand = (MakeCleanDirectoryStep)mkTmpDir;
    String srcDirPath =
        "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/katana/katana_manifest__srcs";
    assertEquals(
        "Fourth command should delete the temp source directory to be written by the genrule.",
        tempDirPath,
        thirdMkdirCommand.getPath());

    MkdirAndSymlinkFileStep linkSource1 = (MkdirAndSymlinkFileStep) steps.get(4);
    assertEquals("/opt/local/fbandroid/src/com/facebook/katana/convert_to_katana.py",
        linkSource1.getSource().getAbsolutePath());
    assertEquals(srcDirPath + "/convert_to_katana.py", linkSource1.getTarget().getAbsolutePath());

    MkdirAndSymlinkFileStep linkSource2 = (MkdirAndSymlinkFileStep) steps.get(5);
    assertEquals("/opt/local/fbandroid/src/com/facebook/katana/AndroidManifest.xml",
        linkSource2.getSource().getAbsolutePath());
    assertEquals(srcDirPath + "/AndroidManifest.xml", linkSource2.getTarget().getAbsolutePath());

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
        .put("TMP", tempDirPath)
        .put("SRCDIR", srcDirPath)
        .build(),
        genruleCommand.getEnvironmentVariables());
    assertEquals(
        ImmutableList.of("/bin/bash", "-c", "python convert_to_katana.py AndroidManifest.xml > $OUT"),
        genruleCommand.getShellCommand(executionContext));
  }

  @Test
  public void testBuildTargetPattern() {
    Pattern buildTargetPattern = Genrule.BUILD_TARGET_PATTERN;
    assertTrue(buildTargetPattern.matcher("${//first-party/orca/orcaapp:manifest}").matches());
    assertFalse(buildTargetPattern.matcher("\\${//first-party/orca/orcaapp:manifest}").matches());
    assertFalse(buildTargetPattern.matcher("${first-party/orca/orcaapp:manifest}").matches());
    assertTrue(buildTargetPattern.matcher("${:manifest}").matches());
  }

  @Test
  public void testReplaceBinaryBuildRuleRefsInCmd() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(buildRuleIndex);

    String originalCmd = "${//java/com/facebook/util:ManifestGenerator} $OUT";
    String contextBasePath = "java/com/facebook/util";
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);

    Genrule rule = createGenrule(buildRuleIndex, originalCmd, contextBasePath, deps);

    // Interpolate the build target in the genrule cmd string.
    String transformedString = rule.replaceBinaryBuildRuleRefsInCmd();

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
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(buildRuleIndex);

    String originalCmd = "${:ManifestGenerator} $OUT";
    String contextBasePath = "java/com/facebook/util";
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);

    Genrule rule = createGenrule(buildRuleIndex, originalCmd, contextBasePath, deps);

    // Interpolate the build target in the genrule cmd string.
    String transformedString = rule.replaceBinaryBuildRuleRefsInCmd();

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
  public void testDepsGenrule() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    JavaBinaryRule javaBinary = createSampleJavaBinaryRule(buildRuleIndex);

    // Interpolate the build target in the genrule cmd string.
    String originalCmd = "${:ManifestGenerator} $OUT";
    Set<? extends BuildRule> deps = ImmutableSet.of(javaBinary);
    String contextBasePath = "java/com/facebook/util";

    Genrule rule = createGenrule(buildRuleIndex, originalCmd, contextBasePath, deps);

    String transformedString = rule.replaceBinaryBuildRuleRefsInCmd();

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
    BuildTarget target = BuildTargetFactory.newInstance("//:example");
    Genrule rule = Genrule.newGenruleBuilder()
        .setRelativeToAbsolutePathFunction(relativeToAbsolutePathFunction)
        .setBuildTarget(target)
        .setCmd("ignored")
        .addSrc("in-dir.txt")
        .addSrc("foo/bar.html")
        .addSrc("other/place.txt")
        .setOut("example-file")
        .setArtifactCache(artifactCache)
        .build(ImmutableMap.<String, BuildRule>of());

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    rule.addSymlinkCommands(builder);
    ImmutableList<Step> commands = builder.build();

    String baseTmpPath = "/opt/local/fbandroid/" + GEN_DIR + "/example__srcs/";
    String sourcePath = "/opt/local/fbandroid/";

    assertEquals(3, commands.size());
    MkdirAndSymlinkFileStep linkCmd = (MkdirAndSymlinkFileStep) commands.get(0);
    assertEquals(sourcePath + "in-dir.txt", linkCmd.getSource().getAbsolutePath());
    assertEquals(baseTmpPath + "in-dir.txt", linkCmd.getTarget().getAbsolutePath());

    linkCmd = (MkdirAndSymlinkFileStep) commands.get(1);
    assertEquals(sourcePath + "foo/bar.html", linkCmd.getSource().getAbsolutePath());
    assertEquals(baseTmpPath + "foo/bar.html", linkCmd.getTarget().getAbsolutePath());

    linkCmd = (MkdirAndSymlinkFileStep) commands.get(2);
    assertEquals(sourcePath + "other/place.txt", linkCmd.getSource().getAbsolutePath());
    assertEquals(baseTmpPath + "other/place.txt", linkCmd.getTarget().getAbsolutePath());
  }

  private JavaBinaryRule createSampleJavaBinaryRule(Map<String, BuildRule> buildRuleIndex) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    JavaLibraryRule javaLibrary = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/util:util"))
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .addSrc("java/com/facebook/util/ManifestGenerator.java")
        .setArtifactCache(new NoopArtifactCache())
        .build(buildRuleIndex);
    buildRuleIndex.put(javaLibrary.getFullyQualifiedName(), javaLibrary);

    JavaBinaryRule javaBinary = JavaBinaryRule.newJavaBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/util:ManifestGenerator"))
        .setMainClass("com.facebook.util.ManifestGenerator")
        .addDep(javaLibrary.getFullyQualifiedName())
        .setArtifactCache(new NoopArtifactCache())
        .build(buildRuleIndex);
    buildRuleIndex.put(javaBinary.getFullyQualifiedName(), javaBinary);

    return javaBinary;
  }

  private Genrule createGenrule(Map<String, BuildRule> buildRuleIndex,
                                String originalCmd,
                                String contextBasePath,
                                Set<? extends BuildRule> deps) {
    BuildTarget target = BuildTargetFactory.newInstance(
        String.format("//%s:genrule", contextBasePath));

    Builder ruleBuilder = Genrule.newGenruleBuilder()
        .setRelativeToAbsolutePathFunction(relativeToAbsolutePathFunction)
        .setBuildTarget(target)
        .setCmd(originalCmd)
        .setOut("example-file")
        .setArtifactCache(artifactCache);

    for (BuildRule dep : deps) {
      ruleBuilder.addDep(dep.getFullyQualifiedName());
    }

    return ruleBuilder.build(buildRuleIndex);
  }
}

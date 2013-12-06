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

package com.facebook.buck.android;

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.NonCheckingBuildRuleFactoryParams;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.testutil.IdentityPathRelativizer;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Unit test for {@link com.facebook.buck.android.ApkGenrule}.
 */
public class ApkGenruleTest {

  private static final Function<String, Path> relativeToAbsolutePathFunction =
      new Function<String, Path>() {
        @Override
        public Path apply(String path) {
          return Paths.get("/opt/local/fbandroid", path);
        }
      };

  private void createSampleAndroidBinaryRule(BuildRuleResolver ruleResolver) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildTarget libAndroidTarget = BuildTargetFactory.newInstance("//:lib-android");
    ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(libAndroidTarget)
        .addSrc("java/com/facebook/util/Facebook.java"));

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    ruleResolver.buildAndAddToIndex(
        Keystore.newKeystoreBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(keystoreTarget)
        .setStore("keystore/debug.keystore")
        .setProperties("keystore/debug.keystore.properties")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    ruleResolver.buildAndAddToIndex(
        AndroidBinaryRule.newAndroidBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest(new FileSourcePath("AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystoreTarget)
        .addDep(libAndroidTarget)
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));
  }

  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testCreateAndRunApkGenrule() throws IOException, NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    createSampleAndroidBinaryRule(ruleResolver);
    Map<String, ?> instance = new ImmutableMap.Builder<String, Object>()
        .put("name", "fb4a_signed")
        .put("srcs", ImmutableList.<String>of("signer.py", "key.properties"))
        .put("cmd", "python signer.py $APK key.properties > $OUT")
        .put("apk", ":fb4a")
        .put("out", "signed_fb4a.apk")
        .put("deps", ImmutableList.<Object>of())
        .build();

    // From the Python object, create a ApkGenruleBuildRuleFactory to create a ApkGenrule.Builder
    // that builds a ApkGenrule from the Python object.
    BuildTargetParser parser = EasyMock.createNiceMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse(EasyMock.eq(":fb4a"), EasyMock.anyObject(ParseContext.class)))
        .andStubReturn(BuildTargetFactory.newInstance("//:fb4a"));
    EasyMock.replay(parser);

    BuildTarget buildTarget = new BuildTarget("//src/com/facebook", "sign_fb4a");
    BuildRuleFactoryParams params = NonCheckingBuildRuleFactoryParams.
        createNonCheckingBuildRuleFactoryParams(
            instance,
            parser,
            buildTarget);

    ApkGenruleBuildRuleFactory factory = new ApkGenruleBuildRuleFactory();
    ApkGenrule.Builder builder = factory.newInstance(params);
    builder.setRelativeToAbsolutePathFunctionForTesting(relativeToAbsolutePathFunction);
    ApkGenrule apkGenrule = (ApkGenrule) ruleResolver.buildAndAddToIndex(builder);

    // Verify all of the observers of the Genrule.
    String expectedApkOutput =
        "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/sign_fb4a.apk";
    assertEquals(BuildRuleType.APK_GENRULE, apkGenrule.getType());
    assertEquals(expectedApkOutput,
        apkGenrule.getAbsoluteOutputFilePath());
    BuildContext buildContext = BuildContext.builder()
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setStepRunner(EasyMock.createNiceMock(StepRunner.class))
        .setProjectFilesystem(EasyMock.createNiceMock(ProjectFilesystem.class))
        .setArtifactCache(EasyMock.createMock(ArtifactCache.class))
        .setJavaPackageFinder(EasyMock.createNiceMock(JavaPackageFinder.class))
        .setEventBus(BuckEventBusFactory.newInstance())
        .build();
    ImmutableSortedSet<String> inputsToCompareToOutputs = ImmutableSortedSet.of(
        "src/com/facebook/key.properties",
        "src/com/facebook/signer.py");
    assertEquals(inputsToCompareToOutputs,
        apkGenrule.getInputsToCompareToOutput());

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = apkGenrule.getBuildSteps(buildContext, new FakeBuildableContext());
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
            apkGenrule.getPathToOutputFile()),
        rmCommand.getShellCommand(executionContext));

    Step secondStep = steps.get(1);
    assertTrue(secondStep instanceof MkdirStep);
    MkdirStep mkdirCommand = (MkdirStep) secondStep;
    assertEquals(
        "Second command should make sure the output directory exists.",
        Paths.get(GEN_DIR + "/src/com/facebook/"),
        mkdirCommand.getPath(executionContext));

    Step thirdStep = steps.get(2);
    assertTrue(thirdStep instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep secondMkdirCommand = (MakeCleanDirectoryStep) thirdStep;
    String relativePathToTmpDir = GEN_DIR + "/src/com/facebook/sign_fb4a__tmp";
    assertEquals(
        "Third command should make sure the temp directory exists.",
        relativePathToTmpDir,
        secondMkdirCommand.getPath());

    Step fourthStep = steps.get(3);
    assertTrue(fourthStep instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep thirdMkdirCommand = (MakeCleanDirectoryStep) fourthStep;
    String relativePathToSrcDir = GEN_DIR + "/src/com/facebook/sign_fb4a__srcs";
    assertEquals(
        "Fourth command should make sure the temp directory exists.",
        relativePathToSrcDir,
        thirdMkdirCommand.getPath());

    MkdirAndSymlinkFileStep linkSource1 = (MkdirAndSymlinkFileStep) steps.get(4);
    assertEquals("src/com/facebook/signer.py", linkSource1.getSource());
    assertEquals(relativePathToSrcDir + "/signer.py", linkSource1.getTarget());

    MkdirAndSymlinkFileStep linkSource2 = (MkdirAndSymlinkFileStep) steps.get(5);
    assertEquals("src/com/facebook/key.properties", linkSource2.getSource());
    assertEquals(relativePathToSrcDir + "/key.properties", linkSource2.getTarget());

    Step seventhStep = steps.get(6);
    assertTrue(seventhStep instanceof ShellStep);
    ShellStep genruleCommand = (ShellStep) seventhStep;
    assertEquals("genrule", genruleCommand.getShortName());
    assertEquals(new ImmutableMap.Builder<String, String>()
        .put("APK", relativeToAbsolutePathFunction.apply(GEN_DIR + "/fb4a.apk").toString())
        .put("OUT", expectedApkOutput).build(),
        genruleCommand.getEnvironmentVariables(executionContext));
    assertEquals(
        ImmutableList.of("/bin/bash", "-e", "-c", "python signer.py $APK key.properties > $OUT"),
        genruleCommand.getShellCommand(executionContext));

    EasyMock.verify(parser);
  }

  private ExecutionContext newEmptyExecutionContext() {
    return ExecutionContext.builder()
        .setConsole(new TestConsole())
        .setProjectFilesystem(new ProjectFilesystem(new File(".")) {
          @Override
          public Function<String, Path> getPathRelativizer() {
            return IdentityPathRelativizer.getIdentityRelativizer();
          }

          @Override
          public Path resolve(Path path) {
            return path;
          }
        })
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.LINUX) // Fix platform to Linux to use bash in genrule.
        .build();
  }
}

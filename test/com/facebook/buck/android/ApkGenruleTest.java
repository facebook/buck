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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.sandbox.NoSandboxExecutionStrategy;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ApkGenruleTest {

  private void createSampleAndroidBinaryRule(
      ActionGraphBuilder graphBuilder, ProjectFilesystem filesystem)
      throws NoSuchBuildTargetException {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    BuildTarget libAndroidTarget = BuildTargetFactory.newInstance("//:lib-android");
    BuildRule androidLibRule =
        JavaLibraryBuilder.createBuilder(libAndroidTarget)
            .addSrc(Paths.get("java/com/facebook/util/Facebook.java"))
            .build(graphBuilder, filesystem);

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    Keystore keystore =
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of(filesystem, "keystore/debug.keystore"))
            .setProperties(FakeSourcePath.of(filesystem, "keystore/debug.keystore.properties"))
            .build(graphBuilder, filesystem);

    AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
        .setOriginalDeps(ImmutableSortedSet.of(androidLibRule.getBuildTarget()))
        .setKeystore(keystore.getBuildTarget())
        .build(graphBuilder, filesystem);
  }

  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testCreateAndRunApkGenrule() throws IOException, NoSuchBuildTargetException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    FileSystem fileSystem = projectFilesystem.getRootPath().getFileSystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = graphBuilder;
    createSampleAndroidBinaryRule(graphBuilder, projectFilesystem);

    // From the Python object, create a ApkGenruleBuildRuleFactory to create a ApkGenrule.Builder
    // that builds a ApkGenrule from the Python object.
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//:fb4a");

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//src/com/facebook:sign_fb4a");

    ApkGenruleDescription description =
        new ApkGenruleDescription(
            ApkGenruleBuilder.getToolchainProvider(),
            FakeBuckConfig.builder().build(),
            new NoSandboxExecutionStrategy());
    ApkGenruleDescriptionArg arg =
        ApkGenruleDescriptionArg.builder()
            .setName(buildTarget.getShortName())
            .setApk(new FakeInstallable(apkTarget).getBuildTarget())
            .setBash(StringWithMacrosUtils.format(""))
            .setCmd(StringWithMacrosUtils.format("python signer.py $APK key.properties > $OUT"))
            .setCmdExe(StringWithMacrosUtils.format(""))
            .setOut("signed_fb4a.apk")
            .setSrcs(
                SourceSet.ofUnnamedSources(
                    ImmutableSortedSet.of(
                        PathSourcePath.of(
                            projectFilesystem, fileSystem.getPath("src/com/facebook/signer.py")),
                        PathSourcePath.of(
                            projectFilesystem,
                            fileSystem.getPath("src/com/facebook/key.properties")))))
            .build();
    BuildRuleParams params = TestBuildRuleParams.create();
    ApkGenrule apkGenrule =
        (ApkGenrule)
            description.createBuildRule(
                TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                buildTarget,
                params,
                arg);
    graphBuilder.addToIndex(apkGenrule);

    // Verify all of the observers of the Genrule.
    Path expectedApkOutput =
        projectFilesystem.resolve(
            BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s")
                .resolve("sign_fb4a.apk"));
    assertEquals(
        expectedApkOutput,
        ruleFinder.getSourcePathResolver().getAbsolutePath(apkGenrule.getSourcePathToOutput()));
    assertEquals(
        "The apk that this rule is modifying must have the apk in its deps.",
        ImmutableSet.of(apkTarget.toString()),
        apkGenrule.getBuildDeps().stream()
            .map(Object::toString)
            .collect(ImmutableSet.toImmutableSet()));
    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver())
            .withBuildCellRootPath(projectFilesystem.getRootPath().getPath());
    assertThat(
        graphBuilder
            .getSourcePathResolver()
            .filterInputsToCompareToOutput(apkGenrule.getBuildable().getSrcs().getPaths()),
        Matchers.containsInAnyOrder(
            fileSystem.getPath("src/com/facebook/signer.py"),
            fileSystem.getPath("src/com/facebook/key.properties")));

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = apkGenrule.getBuildSteps(buildContext, new FakeBuildableContext());
    MoreAsserts.assertStepsNames(
        "",
        ImmutableList.of(
            "rm",
            "rm",
            "mkdir",
            "rm",
            "mkdir",
            "rm",
            "mkdir",
            "rm",
            "mkdir",
            "genrule_srcs_link_tree",
            "genrule"),
        steps);

    Optional<Step> maybeGenruleStep =
        steps.stream().filter(step -> step instanceof AbstractGenruleStep).findFirst();
    assertTrue(maybeGenruleStep.isPresent());
    ExecutionContext executionContext = newEmptyExecutionContext();
    AbstractGenruleStep genruleStep = (AbstractGenruleStep) maybeGenruleStep.get();
    assertEquals("genrule", genruleStep.getShortName());
    ImmutableMap<String, String> environmentVariables =
        genruleStep.getEnvironmentVariables(executionContext);
    assertEquals(
        new ImmutableMap.Builder<String, String>()
            .put(
                "APK",
                projectFilesystem
                    .resolve(BuildTargetPaths.getGenPath(projectFilesystem, apkTarget, "%s.apk"))
                    .toString())
            .put("OUT", expectedApkOutput.toString())
            .build(),
        environmentVariables);

    Path scriptFilePath = genruleStep.getScriptFilePath(executionContext);
    String scriptFileContents = genruleStep.getScriptFileContents(executionContext);
    assertEquals(
        ImmutableList.of("/bin/bash", "-e", scriptFilePath.toString()),
        genruleStep.getShellCommand(executionContext));
    assertEquals("python signer.py $APK key.properties > $OUT", scriptFileContents);
  }

  private ExecutionContext newEmptyExecutionContext() {
    return TestExecutionContext.newBuilder()
        .setPlatform(Platform.LINUX) // Fix platform to Linux to use bash in genrule.
        .build();
  }

  private static class FakeInstallable extends FakeBuildRule implements HasInstallableApk {

    public FakeInstallable(BuildTarget buildTarget) {
      super(buildTarget);
    }

    @Override
    public ApkInfo getApkInfo() {
      return ImmutableApkInfo.of(
          FakeSourcePath.of("spoof"), FakeSourcePath.of("buck-out/gen/fb4a.apk"), Optional.empty());
    }
  }
}

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

package com.facebook.buck.shell;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.DefaultBuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.DefaultOutputPathResolver;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.shell.programrunner.DirectProgramRunner;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GenruleBuildableTest {
  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  private ProjectFilesystem filesystem;

  @Before
  public void newFakeFilesystem() {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  @Test
  public void ensureFilesInSubdirectoriesAreKeptInSubDirectories() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver())
            .withBuildCellRootPath(filesystem.getRootPath().getPath());
    BuildTarget target = BuildTargetFactory.newInstance("//:example");
    Path srcPath = filesystem.getBuckPaths().getGenDir().resolve("example__srcs");
    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .addSrcs(PathSourcePath.of(filesystem, filesystem.getPath("in-dir.txt")))
            .addSrcs(PathSourcePath.of(filesystem, filesystem.getPath("foo/bar.html")))
            .addSrcs(PathSourcePath.of(filesystem, filesystem.getPath("other/place.txt")))
            .setOut(Optional.of("example-file"))
            .build()
            .toBuildable();

    ImmutableList.Builder<Step> builder = ImmutableList.builder();
    buildable.addSymlinkCommands(context, filesystem, srcPath, builder);
    ImmutableList<Step> commands = builder.build();

    MoreAsserts.assertStepsNames("", ImmutableList.of("genrule_srcs_link_tree"), commands);
    assertEquals(
        new SymlinkTreeStep(
            "genrule_srcs",
            filesystem,
            srcPath,
            ImmutableMap.of(
                filesystem.getPath("in-dir.txt"),
                filesystem.getPath("in-dir.txt"),
                filesystem.getPath("foo/bar.html"),
                filesystem.getPath("foo/bar.html"),
                filesystem.getPath("other/place.txt"),
                filesystem.getPath("other/place.txt"))),
        commands.get(0));
  }

  @Test
  public void testGenruleUsesSpacesForSrcsVariableDelimiterByDefault() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:genrule");
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);

    SourcePath path1 = PathSourcePath.of(filesystem, filesystem.getPath("path 1.txt"));
    SourcePath path2 = PathSourcePath.of(filesystem, filesystem.getPath("dir", "path 2.txt"));

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setSrcs(ImmutableSet.of(path1, path2))
            .setCmd("echo \"Hello, world\" >> $OUT")
            .setOut(Optional.of("output.txt"))
            .build()
            .toBuildable();

    String expected =
        String.format(
            "%s %s", pathResolver.getAbsolutePath(path2), pathResolver.getAbsolutePath(path1));
    ImmutableMap.Builder<String, String> actualEnvVarsBuilder = ImmutableMap.builder();

    Path srcPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__tmp");
    buildable.addEnvironmentVariables(
        pathResolver, outputPathResolver, filesystem, srcPath, tmpPath, actualEnvVarsBuilder);

    assertEquals(expected, actualEnvVarsBuilder.build().get("SRCS"));
  }

  @Test
  public void testGenruleUsesProvidedDelimiterForSrcsVariable() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//:genrule");
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);

    SourcePath path1 = PathSourcePath.of(filesystem, filesystem.getPath("path 1.txt"));
    SourcePath path2 = PathSourcePath.of(filesystem, filesystem.getPath("dir name", "path 2.txt"));

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setSrcs(ImmutableSet.of(path1, path2))
            .setCmd("echo \"Hello, world\" >> $OUT")
            .setOut(Optional.of("output.txt"))
            .setEnvironmentExpansionSeparator("//")
            .build()
            .toBuildable();

    String expected =
        String.format(
            "%s//%s", pathResolver.getAbsolutePath(path2), pathResolver.getAbsolutePath(path1));
    ImmutableMap.Builder<String, String> actualEnvVarsBuilder = ImmutableMap.builder();

    Path srcPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__tmp");
    buildable.addEnvironmentVariables(
        pathResolver, outputPathResolver, filesystem, srcPath, tmpPath, actualEnvVarsBuilder);

    assertEquals(expected, actualEnvVarsBuilder.build().get("SRCS"));
  }

  @Test
  public void testShouldIncludeAndroidSpecificEnvInEnvironmentIfPresent() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    AndroidPlatformTarget android =
        AndroidPlatformTarget.of(
            "android",
            filesystem.getPath(""),
            Collections.emptyList(),
            () -> new SimpleTool("aapt"),
            new ConstantToolProvider(new SimpleTool("aapt2")),
            filesystem.getPath(""),
            filesystem.getPath(""),
            filesystem.getPath("zipalign"),
            filesystem.getPath("."),
            filesystem.getPath(""),
            filesystem.getPath(""),
            filesystem.getPath(""),
            filesystem.getPath(""));
    Path sdkDir = filesystem.getPath("opt", "users", "android_sdk");
    Path ndkDir = filesystem.getPath("opt", "users", "android_ndk");

    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(AndroidPlatformTarget.DEFAULT_NAME, android)
            .withToolchain(
                AndroidNdk.DEFAULT_NAME, AndroidNdk.of("12", ndkDir, false, new ExecutableFinder()))
            .withToolchain(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.of(sdkDir))
            .build();

    GenruleAndroidTools androidTools =
        GenruleAndroidTools.of(
            AndroidTools.getAndroidTools(
                toolchainProvider, UnconfiguredTargetConfiguration.INSTANCE),
            target,
            graphBuilder);
    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setAndroidTools(androidTools)
            .setBash("echo something > $OUT")
            .setOut(Optional.of("file"))
            .build()
            .toBuildable();

    Path srcPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__tmp");

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    buildable.addEnvironmentVariables(
        graphBuilder.getSourcePathResolver(),
        outputPathResolver,
        filesystem,
        srcPath,
        tmpPath,
        builder);
    ImmutableMap<String, String> env = builder.build();

    assertEquals(Paths.get(".").toString(), env.get("DX"));
    assertEquals(Paths.get("zipalign").toString(), env.get("ZIPALIGN"));
    assertEquals("aapt", env.get("AAPT"));
    assertEquals("aapt2", env.get("AAPT2"));
    assertEquals(sdkDir.toString(), env.get("ANDROID_HOME"));
    assertEquals(sdkDir.toString(), env.get("ANDROID_SDK_ROOT"));
    assertEquals(ndkDir.toString(), env.get("NDK_HOME"));
  }

  @Test
  public void shouldPreventTheParentBuckdBeingUsedIfARecursiveBuckCallIsMade() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setBash("echo somthing > $OUT")
            .setOut(Optional.of("file"))
            .build()
            .toBuildable();

    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);
    Path srcPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__tmp");

    buildable.addEnvironmentVariables(
        graphBuilder.getSourcePathResolver(),
        outputPathResolver,
        filesystem,
        srcPath,
        tmpPath,
        builder);

    assertEquals("1", builder.build().get("NO_BUCKD"));
  }

  @Rule public ExpectedException humanReadableExceptionRule = ExpectedException.none();

  /**
   * Tests that GenruleBuildable emits a zip-scrub step if the output file is a zipfile and that it
   * uses an absolute path to the output file as an input to the zip-scrub step.
   */
  @Test
  public void shouldUseAnAbsolutePathForZipScrubberStep() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setBash("echo something > $OUT")
            .setOut(Optional.of("output.zip"))
            .build()
            .toBuildable();

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver(), filesystem);

    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);
    BuildCellRelativePathFactory buildCellRelativePathFactory =
        new DefaultBuildCellRelativePathFactory(
            buildContext.getBuildCellRootPath(), filesystem, Optional.of(outputPathResolver));
    ImmutableList<Step> steps =
        buildable.getBuildSteps(
            buildContext, filesystem, outputPathResolver, buildCellRelativePathFactory);

    Optional<Step> scrubberStep =
        steps.stream().filter(step -> step instanceof ZipScrubberStep).findFirst();
    assertTrue("GenruleBuildable didn't generate ZipScrubber", scrubberStep.isPresent());

    ZipScrubberStep zipScrubberStep = (ZipScrubberStep) scrubberStep.get();
    assertTrue(zipScrubberStep.getZipAbsolutePath().isAbsolute());
  }

  /**
   * Tests that, even with out is a filepath with nested directories, genrule only creates the root
   * output directory.
   */
  @Test
  public void shouldOnlyCreateOutputBaseDirectoryForNestedOutput() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setBash("echo something > $OUT")
            .setOut(Optional.of("nested/file/out.cpp"))
            .build()
            .toBuildable();

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver(), filesystem);
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);
    BuildCellRelativePathFactory buildCellRelativePathFactory =
        new DefaultBuildCellRelativePathFactory(
            buildContext.getBuildCellRootPath(), filesystem, Optional.of(outputPathResolver));
    ImmutableList<Step> steps =
        buildable.getBuildSteps(
            buildContext, filesystem, outputPathResolver, buildCellRelativePathFactory);

    Path targetGenrulePath = BuildTargetPaths.getGenPath(filesystem, target, "%s");
    Optional<Step> mkdir =
        steps.stream()
            .filter(
                step ->
                    step instanceof MkdirStep
                        && ((MkdirStep) step)
                            .getPath()
                            .getPathRelativeToBuildCellRoot()
                            .equals(targetGenrulePath))
            .findFirst();
    assertTrue("GenruleBuildable didn't generate correct mkdir", mkdir.isPresent());
  }

  @Test
  public void outputPathShouldBeNormalized() {
    ImmutableSet<String> unnormalizedPaths =
        ImmutableSet.<String>builder().add("foo/./bar/./.").add(".").add("./foo").build();

    for (String out : unnormalizedPaths) {
      BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
      GenruleBuildable buildable =
          ImmutableGenruleBuildableBuilder.builder()
              .setBuildTarget(target)
              .setFilesystem(filesystem)
              .setBash("echo something > $OUT")
              .setOut(Optional.of(out))
              .build()
              .toBuildable();

      OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);
      Path outputPath =
          outputPathResolver.resolvePath(
              Iterables.getOnlyElement(buildable.getOutputs(OutputLabel.defaultLabel())));
      assertEquals(outputPath, outputPath.normalize());
    }
  }

  @Test
  public void defaultOutsIsEmpty() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setBash("echo something > $OUT")
            .setOuts(
                Optional.of(ImmutableMap.of(OutputLabel.of("label1"), ImmutableSet.of("output1a"))))
            .build()
            .toBuildable();

    assertThat(buildable.getOutputs(OutputLabel.defaultLabel()), Matchers.empty());
  }

  @Test
  public void canGetSingleNamedOutput() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(fakeProjectFileSystem, target);
    Path rootPath = outputPathResolver.getRootPath();

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setBash("echo something")
            .setOuts(
                Optional.of(
                    ImmutableMap.of(
                        OutputLabel.of("label1"),
                        ImmutableSet.of("output1a", "output1b"),
                        OutputLabel.of("label2"),
                        ImmutableSet.of("output2a"))))
            .build()
            .toBuildable();

    ImmutableSet<Path> actual =
        buildable.getOutputs(OutputLabel.of("label2")).stream()
            .map(p -> outputPathResolver.resolvePath(p))
            .collect(ImmutableSet.toImmutableSet());

    assertThat(actual, Matchers.containsInAnyOrder(rootPath.resolve("output2a")));
  }

  @Test
  public void canGetMultipleNamedOutputs() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(fakeProjectFileSystem, target);
    Path rootPath = outputPathResolver.getRootPath();

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setBash("echo something")
            .setOuts(
                Optional.of(
                    ImmutableMap.of(
                        OutputLabel.of("label1"),
                        ImmutableSet.of("output1a", "output1b"),
                        OutputLabel.of("label2"),
                        ImmutableSet.of("output2a"))))
            .build()
            .toBuildable();

    ImmutableSet<Path> actual =
        buildable.getOutputs(OutputLabel.of("label1")).stream()
            .map(p -> outputPathResolver.resolvePath(p))
            .collect(ImmutableSet.toImmutableSet());

    assertThat(
        actual,
        Matchers.containsInAnyOrder(rootPath.resolve("output1a"), rootPath.resolve("output1b")));
  }

  @Test
  public void throwsIfGetNonExistentLabel() {
    expectedThrownException.expect(HumanReadableException.class);
    expectedThrownException.expectMessage(
        "Cannot find output label [nonexistent] for target //example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(BuildTargetFactory.newInstance("//example:genrule"))
            .setFilesystem(new FakeProjectFilesystem())
            .setBash("echo something")
            .setOuts(
                Optional.of(
                    ImmutableMap.of(
                        OutputLabel.of("label1"),
                        ImmutableSet.of("output1a", "output1b"),
                        OutputLabel.of("label2"),
                        ImmutableSet.of("output2a"))))
            .build()
            .toBuildable();

    buildable.getOutputs(OutputLabel.of("nonexistent"));
  }

  @Test
  public void throwsIfGetOutputsIsCalledWhenNoMultipleOutputs() {
    expectedThrownException.expect(IllegalArgumentException.class);
    expectedThrownException.expectMessage(
        "Unexpected output label [harro] for target //example:genrule. Use 'outs' instead of 'out' to use output labels");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(BuildTargetFactory.newInstance("//example:genrule"))
            .setFilesystem(new FakeProjectFilesystem())
            .setBash("echo something")
            .setOut(Optional.of("output.txt"))
            .build()
            .toBuildable();

    buildable.getOutputs(OutputLabel.of("harro"));
  }

  @Test
  public void canGetDefaultOutputWithNoMultipleOutputs() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(new FakeProjectFilesystem(), target);

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setBash("echo something")
            .setOut(Optional.of("output.txt"))
            .build()
            .toBuildable();

    ImmutableSet<Path> actual =
        buildable.getOutputs(OutputLabel.defaultLabel()).stream()
            .map(p -> outputPathResolver.resolvePath(p))
            .collect(ImmutableSet.toImmutableSet());

    // "out" uses the legacy path that isn't suffixed with "__"
    assertThat(
        actual,
        Matchers.containsInAnyOrder(
            BuildTargetPaths.getGenPath(new FakeProjectFilesystem(), target, "%s")
                .resolve("output.txt")));
  }

  @Test
  public void outputPathsSetsOutToUnderscoresSuffixedOutputsDirectory() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(fakeProjectFileSystem, target);
    Path srcPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(filesystem, target, "%s__tmp");

    ImmutableMap.Builder<String, String> envVarsBuilder = ImmutableMap.builder();
    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setBash("echo something")
            .setOuts(
                Optional.of(
                    ImmutableMap.of(OutputLabel.of("named"), ImmutableSet.of("output.txt"))))
            .build()
            .toBuildable();

    buildable.addEnvironmentVariables(
        pathResolver, outputPathResolver, fakeProjectFileSystem, srcPath, tmpPath, envVarsBuilder);

    assertThat(
        envVarsBuilder.build().get("OUT"),
        Matchers.equalTo(
            fakeProjectFileSystem.resolve(outputPathResolver.getRootPath()).toString()));
  }

  @Test
  public void outputPathSetsOutToOutFile() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(fakeProjectFileSystem, target);
    Path srcPath = BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s__tmp");

    ImmutableMap.Builder<String, String> envVarsBuilder = ImmutableMap.builder();
    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setBash("echo something")
            .setOut(Optional.of("output.txt"))
            .build()
            .toBuildable();

    buildable.addEnvironmentVariables(
        pathResolver, outputPathResolver, fakeProjectFileSystem, srcPath, tmpPath, envVarsBuilder);
    assertThat(
        envVarsBuilder.build().get("OUT"),
        Matchers.equalTo(
            fakeProjectFileSystem
                .getRootPath()
                .resolve(
                    BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s")
                        .resolve("output.txt"))
                .toString()));
  }

  @Test
  public void remoteTrueIsRespected() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setBash("echo something")
            .setOut(Optional.of("output.txt"))
            .setRemote(true)
            .build()
            .toBuildable();

    assertTrue(buildable.shouldExecuteRemotely());
  }

  @Test
  public void remoteFalseIsRespected() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setBash("echo something")
            .setOut(Optional.of("output.txt"))
            .setRemote(false)
            .build()
            .toBuildable();

    assertFalse(buildable.shouldExecuteRemotely());
  }

  @Test
  public void throwsIfExpectedNamedOutputNotPresentForMultipleOutputs() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver())
            .withBuildCellRootPath(fakeProjectFileSystem.getRootPath().getPath());
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(fakeProjectFileSystem, target);
    Path srcPath = BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s__tmp");

    expectedThrownException.expect(BuckUncheckedExecutionException.class);
    expectedThrownException.expectCause(Matchers.instanceOf(FileNotFoundException.class));
    // Note that Windows uses backslashes while Unix uses forward slashes
    expectedThrownException.expectMessage(
        String.format(
            "Expected file %s to be written from genrule //example:genrule. File was not present",
            outputPathResolver.resolvePath(new OutputPath("output.txt"))));

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setCmd("echo something")
            .setOuts(
                Optional.of(
                    ImmutableMap.of(OutputLabel.of("named"), ImmutableSet.of("output.txt"))))
            .build()
            .toBuildable();
    AbstractGenruleStep step =
        buildable.createGenruleStep(
            context,
            outputPathResolver,
            fakeProjectFileSystem,
            srcPath,
            tmpPath,
            new DirectProgramRunner() {
              @Override
              public ImmutableList<String> enhanceCommandLine(ImmutableList<String> commandLine) {
                return ImmutableList.of();
              }
            });

    step.execute(TestExecutionContext.newInstance());
  }

  @Test
  public void throwsIfExpectedNamedOutputputNotPresentForSingleOutput() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");
    ProjectFilesystem fakeProjectFileSystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildContext context =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver())
            .withBuildCellRootPath(fakeProjectFileSystem.getRootPath().getPath());
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(fakeProjectFileSystem, target);
    Path srcPath = BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s__srcs");
    Path tmpPath = BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s__tmp");

    expectedThrownException.expect(BuckUncheckedExecutionException.class);
    expectedThrownException.expectCause(Matchers.instanceOf(FileNotFoundException.class));
    // Note that Windows uses backslashes while Unix uses forward slashes
    expectedThrownException.expectMessage(
        String.format(
            "Expected file %s to be written from genrule //example:genrule. File was not present",
            outputPathResolver.resolvePath(
                new PublicOutputPath(
                    BuildTargetPaths.getGenPath(fakeProjectFileSystem, target, "%s")
                        .resolve("output.txt")))));

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(fakeProjectFileSystem)
            .setCmd("echo something")
            .setOut(Optional.of("output.txt"))
            .build()
            .toBuildable();
    AbstractGenruleStep step =
        buildable.createGenruleStep(
            context,
            outputPathResolver,
            fakeProjectFileSystem,
            srcPath,
            tmpPath,
            new DirectProgramRunner() {
              @Override
              public ImmutableList<String> enhanceCommandLine(ImmutableList<String> commandLine) {
                return ImmutableList.of();
              }
            });

    step.execute(TestExecutionContext.newInstance());
  }

  @Test
  public void outputNameIsOutForOutWithDefaultLabel() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setCmd("echo something")
            .setOut(Optional.of("output.txt"))
            .build()
            .toBuildable();

    assertEquals("output.txt", buildable.getOutputName(OutputLabel.defaultLabel()));
  }

  @Test
  public void throwsIfGetOutputNameWithInvalidLabel() {
    expectedThrownException.expect(HumanReadableException.class);
    expectedThrownException.expectMessage(
        "Output label [nonexistent] not found for target //example:genrule");

    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setCmd("echo something")
            .setOuts(
                Optional.of(ImmutableMap.of(OutputLabel.of("label"), ImmutableSet.of("output1"))))
            .build()
            .toBuildable();

    buildable.getOutputName(OutputLabel.of("nonexistent"));
  }

  @Test
  public void throwsIfGetOutputNameForOutWithNonDefaultLabel() {
    expectedThrownException.expect(IllegalArgumentException.class);
    expectedThrownException.expectMessage(
        "Unexpectedly received non-default label [nonexistent] for target //example:genrule");

    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setCmd("echo something")
            .setOut(Optional.of("output.txt"))
            .build()
            .toBuildable();

    buildable.getOutputName(OutputLabel.of("nonexistent"));
  }

  @Test
  public void canGetDifferentOutputNamesForOuts() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setCmd("echo something")
            .setOuts(
                Optional.of(
                    ImmutableMap.of(
                        OutputLabel.of("label1"),
                        ImmutableSet.of("output1a"),
                        OutputLabel.of("label2"),
                        ImmutableSet.of("output2a"))))
            .build()
            .toBuildable();

    assertEquals("output1a", buildable.getOutputName(OutputLabel.of("label1")));
    assertEquals("output2a", buildable.getOutputName(OutputLabel.of("label2")));
  }

  @Test
  public void throwsIfGetOutputNameForDefaultOutputs() {
    expectedThrownException.expect(HumanReadableException.class);
    expectedThrownException.expectMessage(
        "Default outputs not supported for genrule //example:genrule (that uses `outs`). Use named outputs");

    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setCmd("echo something")
            .setOuts(
                Optional.of(
                    ImmutableMap.of(
                        OutputLabel.of("label1"),
                        ImmutableSet.of("output1a"),
                        OutputLabel.of("label2"),
                        ImmutableSet.of("output2a"))))
            .build()
            .toBuildable();

    buildable.getOutputName(OutputLabel.defaultLabel());
  }

  @Test
  public void doesNotOverwriteDefaultOutputsIfDefaultOutputsWereGiven() {
    BuildTarget target = BuildTargetFactory.newInstance("//example:genrule");

    GenruleBuildable buildable =
        ImmutableGenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(new FakeProjectFilesystem())
            .setCmd("echo something")
            .setOuts(
                Optional.of(ImmutableMap.of(OutputLabel.defaultLabel(), ImmutableSet.of("foo"))))
            .build()
            .toBuildable();

    assertEquals("foo", buildable.getOutputName(OutputLabel.defaultLabel()));
  }
}

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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
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
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GenruleBuildableTest {
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
            .withBuildCellRootPath(filesystem.getRootPath());
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:example");
    Path srcPath = filesystem.getBuckPaths().getGenDir().resolve("example__srcs");
    GenruleBuildable buildable =
        GenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .addSrcs(PathSourcePath.of(filesystem, filesystem.getPath("in-dir.txt")))
            .addSrcs(PathSourcePath.of(filesystem, filesystem.getPath("foo/bar.html")))
            .addSrcs(PathSourcePath.of(filesystem, filesystem.getPath("other/place.txt")))
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
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:genrule");
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);

    SourcePath path1 = PathSourcePath.of(filesystem, filesystem.getPath("path 1.txt"));
    SourcePath path2 = PathSourcePath.of(filesystem, filesystem.getPath("dir", "path 2.txt"));

    GenruleBuildable buildable =
        GenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setSrcs(ImmutableList.of(path1, path2))
            .setCmd("echo \"Hello, world\" >> $OUT")
            .setOut("output.txt")
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
    SourcePathResolver pathResolver = graphBuilder.getSourcePathResolver();
    BuildTarget target = BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:genrule");
    OutputPathResolver outputPathResolver = new DefaultOutputPathResolver(filesystem, target);

    SourcePath path1 = PathSourcePath.of(filesystem, filesystem.getPath("path 1.txt"));
    SourcePath path2 = PathSourcePath.of(filesystem, filesystem.getPath("dir name", "path 2.txt"));

    GenruleBuildable buildable =
        GenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setSrcs(ImmutableList.of(path1, path2))
            .setCmd("echo \"Hello, world\" >> $OUT")
            .setOut("output.txt")
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

    BuildTarget target =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//example:genrule");
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
            AndroidTools.getAndroidTools(toolchainProvider), target, graphBuilder);
    GenruleBuildable buildable =
        GenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setAndroidTools(androidTools)
            .setBash("echo something > $OUT")
            .setOut("file")
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
    assertEquals(ndkDir.toString(), env.get("NDK_HOME"));
  }

  @Test
  public void shouldPreventTheParentBuckdBeingUsedIfARecursiveBuckCallIsMade() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//example:genrule");
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    GenruleBuildable buildable =
        GenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setBash("echo somthing > $OUT")
            .setOut("file")
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

  @Test
  public void shouldThrowIfOutPathIsEmpty() {
    humanReadableExceptionRule.expect(HumanReadableException.class);
    humanReadableExceptionRule.expectMessage(
        "The 'out' parameter of genrule //example:genrule is '', which is not a valid file name.");
    BuildTarget target =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//example:genrule");
    GenruleBuildableBuilder.builder()
        .setBuildTarget(target)
        .setFilesystem(filesystem)
        .setOut("")
        .build()
        .toBuildable();
  }

  @Test
  public void shouldThrowIfOutPathIsAbsolute() {
    humanReadableExceptionRule.expect(HumanReadableException.class);
    if (Platform.detect() == Platform.WINDOWS) {
      humanReadableExceptionRule.expectMessage(
          "The 'out' parameter of genrule //example:genrule is 'C:\\opt\\src\\buck\\opt\\stuff', which is not a valid file name.");
    } else {
      humanReadableExceptionRule.expectMessage(
          "The 'out' parameter of genrule //example:genrule is '/opt/src/buck/opt/stuff', which is not a valid file name.");
    }
    BuildTarget target =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//example:genrule");
    GenruleBuildableBuilder.builder()
        .setBuildTarget(target)
        .setFilesystem(filesystem)
        .setOut(filesystem.getPathForRelativePath(filesystem.getPath("opt", "stuff")).toString())
        .build()
        .toBuildable();
  }

  /**
   * Tests that GenruleBuildable emits a zip-scrub step if the output file is a zipfile and that it
   * uses an absolute path to the output file as an input to the zip-scrub step.
   */
  @Test
  public void shouldUseAnAbsolutePathForZipScrubberStep() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//example:genrule");

    GenruleBuildable buildable =
        GenruleBuildableBuilder.builder()
            .setBuildTarget(target)
            .setFilesystem(filesystem)
            .setBash("echo something > $OUT")
            .setOut("output.zip")
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
}

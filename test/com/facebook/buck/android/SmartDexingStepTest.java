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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.SmartDexingStep.DxPseudoRule;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SmartDexingStepTest {
  @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

  private AndroidPlatformTarget androidPlatformTarget =
      AndroidTestUtils.createAndroidPlatformTarget();

  /** Tests whether pseudo rule cache detection is working properly. */
  @Test
  public void testDxPseudoRuleCaching() throws Exception {
    File testIn = new File(tmpDir.getRoot(), "testIn");
    try (ZipOutputStream zipOut =
        new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(testIn)))) {
      zipOut.putNextEntry(new ZipEntry("foobar"));
      zipOut.write(new byte[] {0});
    }

    File outputFile = tmpDir.newFile("out.dex");
    Path outputHashFile = new File(tmpDir.getRoot(), "out.dex.hash").toPath();
    Files.write("dummy", outputHashFile.toFile(), StandardCharsets.UTF_8);

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot().toPath());

    Sha1HashCode actualHashCode = Sha1HashCode.of(Strings.repeat("a", 40));
    DxPseudoRule rule =
        new DxPseudoRule(
            androidPlatformTarget,
            FakeBuildContext.NOOP_CONTEXT,
            filesystem,
            ImmutableMap.of(testIn.toPath(), actualHashCode),
            ImmutableSet.of(testIn.toPath()),
            outputFile.toPath(),
            outputHashFile,
            EnumSet.of(D8Step.Option.NO_OPTIMIZE),
            Optional.empty(),
            XzStep.DEFAULT_COMPRESSION_LEVEL,
            D8Step.D8,
            null,
            false,
            Optional.empty());
    assertFalse("'dummy' is not a matching input hash", rule.checkIsCached());

    // Write the real hash into the output hash file and ensure that checkIsCached now
    // yields true.
    String actualHash = rule.hashInputs();
    assertFalse(actualHash.isEmpty());
    Files.write(actualHash, outputHashFile.toFile(), StandardCharsets.UTF_8);

    assertTrue("Matching input hash should be considered cached", rule.checkIsCached());
  }

  /** Tests whether pseudo rule cache detection is working properly with primary class names. */
  @Test
  public void testDxPseudoRuleCachingWithPrimaryClassNamesFiles() throws Exception {
    File testIn = new File(tmpDir.getRoot(), "testIn");
    try (ZipOutputStream zipOut =
        new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(testIn)))) {
      zipOut.putNextEntry(new ZipEntry("foobar"));
      zipOut.write(new byte[] {0});
    }

    File outputFile = tmpDir.newFile("out.dex");
    Path outputHashFile = new File(tmpDir.getRoot(), "out.dex.hash").toPath();
    Files.write("dummy", outputHashFile.toFile(), StandardCharsets.UTF_8);

    Path primaryDexClassNames = new File(tmpDir.getRoot(), "primary_dex_class_names.txt").toPath();
    Files.write("some_name", primaryDexClassNames.toFile(), StandardCharsets.UTF_8);

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot().toPath());

    Sha1HashCode actualHashCode = Sha1HashCode.of(Strings.repeat("a", 40));
    DxPseudoRule rule =
        new DxPseudoRule(
            androidPlatformTarget,
            FakeBuildContext.NOOP_CONTEXT,
            filesystem,
            ImmutableMap.of(testIn.toPath(), actualHashCode),
            ImmutableSet.of(testIn.toPath()),
            outputFile.toPath(),
            outputHashFile,
            EnumSet.of(D8Step.Option.NO_OPTIMIZE),
            Optional.of(primaryDexClassNames),
            XzStep.DEFAULT_COMPRESSION_LEVEL,
            D8Step.D8,
            null,
            false,
            Optional.empty());
    assertFalse("'dummy' is not a matching input hash", rule.checkIsCached());

    // Write the real hash into the output hash file and ensure that checkIsCached now
    // yields true.
    String actualHash = rule.hashInputs();
    assertFalse(actualHash.isEmpty());
    Files.write(actualHash, outputHashFile.toFile(), StandardCharsets.UTF_8);

    assertTrue("Matching input hash should be considered cached", rule.checkIsCached());
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithXzOutput() {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    FileSystem fileSystem = filesystem.getFileSystem();

    ImmutableList<Path> filesToDex =
        ImmutableList.of(fileSystem.getPath("foo.dex.jar"), fileSystem.getPath("bar.dex.jar"));
    Path outputPath = fileSystem.getPath("classes.dex.jar.xz");
    EnumSet<D8Step.Option> dxOptions = EnumSet.noneOf(D8Step.Option.class);

    ImmutableList.Builder<Step> steps = new ImmutableList.Builder<>();
    AbsPath rootPath = filesystem.getRootPath();
    SmartDexingStep.createDxStepForDxPseudoRule(
        androidPlatformTarget,
        steps,
        FakeBuildContext.NOOP_CONTEXT.withBuildCellRootPath(rootPath),
        filesystem,
        filesToDex,
        outputPath,
        dxOptions,
        Optional.empty(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        D8Step.D8,
        null,
        false,
        Optional.empty());

    MoreAsserts.assertSteps(
        "Steps should repack zip entries and then compress using xz.",
        ImmutableList.of(
            String.format(
                "d8 --output %s --release --lib %s %s %s",
                filesystem.resolve("classes.dex.tmp.jar"),
                androidPlatformTarget.getAndroidJar(),
                filesystem.resolve("foo.dex.jar"),
                filesystem.resolve("bar.dex.jar")),
            "repack classes.dex.tmp.jar in classes.dex.jar",
            "rm -f " + filesystem.resolve("classes.dex.tmp.jar"),
            "dex_meta dexPath:classes.dex.jar dexMetaPath:classes.dex.jar.meta",
            "xz -z -4 --check=crc32 classes.dex.jar"),
        steps.build(),
        TestExecutionContext.newInstance(rootPath));
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithXzOutputNonDefaultCompression() {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    FileSystem fileSystem = filesystem.getFileSystem();

    ImmutableList<Path> filesToDex =
        ImmutableList.of(fileSystem.getPath("foo.dex.jar"), fileSystem.getPath("bar.dex.jar"));
    Path outputPath = fileSystem.getPath("classes.dex.jar.xz");
    EnumSet<D8Step.Option> dxOptions = EnumSet.noneOf(D8Step.Option.class);
    ImmutableList.Builder<Step> steps = new ImmutableList.Builder<>();
    AbsPath rootPath = filesystem.getRootPath();
    SmartDexingStep.createDxStepForDxPseudoRule(
        androidPlatformTarget,
        steps,
        FakeBuildContext.NOOP_CONTEXT.withBuildCellRootPath(rootPath),
        filesystem,
        filesToDex,
        outputPath,
        dxOptions,
        Optional.empty(),
        9,
        D8Step.D8,
        null,
        false,
        Optional.empty());

    MoreAsserts.assertSteps(
        "Steps should repack zip entries and then compress using xz.",
        ImmutableList.of(
            String.format(
                "d8 --output %s --release --lib %s %s %s",
                filesystem.resolve("classes.dex.tmp.jar"),
                androidPlatformTarget.getAndroidJar(),
                filesystem.resolve("foo.dex.jar"),
                filesystem.resolve("bar.dex.jar")),
            "repack classes.dex.tmp.jar in classes.dex.jar",
            "rm -f " + filesystem.resolve("classes.dex.tmp.jar"),
            "dex_meta dexPath:classes.dex.jar dexMetaPath:classes.dex.jar.meta",
            "xz -z -9 --check=crc32 classes.dex.jar"),
        steps.build(),
        TestExecutionContext.newInstance(rootPath));
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithDexOutput() {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    ImmutableList<Path> filesToDex =
        ImmutableList.of(Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.dex");
    EnumSet<D8Step.Option> dxOptions = EnumSet.noneOf(D8Step.Option.class);
    ImmutableList.Builder<Step> steps = new ImmutableList.Builder<>();
    AbsPath rootPath = filesystem.getRootPath();
    SmartDexingStep.createDxStepForDxPseudoRule(
        androidPlatformTarget,
        steps,
        FakeBuildContext.NOOP_CONTEXT.withBuildCellRootPath(rootPath),
        filesystem,
        filesToDex,
        outputPath,
        dxOptions,
        Optional.empty(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        D8Step.D8,
        null,
        false,
        Optional.empty());

    assertEquals(
        String.format(
            "d8 --output %s --release --lib %s %s %s",
            filesystem.resolve("classes.dex"),
            androidPlatformTarget.getAndroidJar(),
            filesystem.resolve("foo.dex.jar"),
            filesystem.resolve("bar.dex.jar")),
        Iterables.getOnlyElement(steps.build())
            .getDescription(TestExecutionContext.newBuilder().build()));
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithMinSdkVersion() {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    ImmutableList<Path> filesToDex =
        ImmutableList.of(Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.dex");
    EnumSet<D8Step.Option> dxOptions = EnumSet.noneOf(D8Step.Option.class);
    ImmutableList.Builder<Step> steps = new ImmutableList.Builder<>();
    AbsPath rootPath = filesystem.getRootPath();
    SmartDexingStep.createDxStepForDxPseudoRule(
        androidPlatformTarget,
        steps,
        FakeBuildContext.NOOP_CONTEXT.withBuildCellRootPath(rootPath),
        filesystem,
        filesToDex,
        outputPath,
        dxOptions,
        Optional.empty(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        D8Step.D8,
        null,
        false,
        Optional.of(28));

    assertEquals(
        String.format(
            "d8 --min-api 28 --output %s --release --lib %s %s %s",
            filesystem.resolve("classes.dex"),
            androidPlatformTarget.getAndroidJar(),
            filesystem.resolve("foo.dex.jar"),
            filesystem.resolve("bar.dex.jar")),
        Iterables.getOnlyElement(steps.build())
            .getDescription(TestExecutionContext.newBuilder().build()));
  }

  @Test
  public void testCreateDxStepForDxPseudoRuleWithDexJarOutput() {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    ImmutableList<Path> filesToDex =
        ImmutableList.of(Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.dex.jar");
    EnumSet<D8Step.Option> dxOptions = EnumSet.noneOf(D8Step.Option.class);
    ImmutableList.Builder<Step> steps = new ImmutableList.Builder<>();
    AbsPath rootPath = filesystem.getRootPath();
    SmartDexingStep.createDxStepForDxPseudoRule(
        androidPlatformTarget,
        steps,
        FakeBuildContext.NOOP_CONTEXT.withBuildCellRootPath(rootPath),
        filesystem,
        filesToDex,
        outputPath,
        dxOptions,
        Optional.empty(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        D8Step.D8,
        null,
        false,
        /* min-sdk-version */ Optional.of(28));

    MoreAsserts.assertSteps(
        "Wrong steps",
        ImmutableList.of(
            String.format(
                "d8 --min-api 28 --output %s --release --lib %s %s %s",
                filesystem.resolve("classes.dex.jar"),
                androidPlatformTarget.getAndroidJar(),
                filesystem.resolve("foo.dex.jar"),
                filesystem.resolve("bar.dex.jar")),
            "dex_meta dexPath:classes.dex.jar dexMetaPath:classes.dex.jar.meta",
            "zip-scrub " + filesystem.resolve("classes.dex.jar")),
        steps.build(),
        TestExecutionContext.newBuilder().build());
  }

  @Test
  public void testInProcesssDescriptionIncludesMinSdkFlag() {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    ImmutableList<Path> filesToDex =
        ImmutableList.of(Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.dex.jar");
    EnumSet<D8Step.Option> dxOptions = EnumSet.noneOf(D8Step.Option.class);
    ImmutableList.Builder<Step> steps = new ImmutableList.Builder<>();
    AbsPath rootPath = filesystem.getRootPath();
    SmartDexingStep.createDxStepForDxPseudoRule(
        androidPlatformTarget,
        steps,
        FakeBuildContext.NOOP_CONTEXT.withBuildCellRootPath(rootPath),
        filesystem,
        filesToDex,
        outputPath,
        dxOptions,
        Optional.empty(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        D8Step.D8,
        null,
        false,
        /* min-sdk-version */ Optional.of(28));

    String description = steps.build().get(0).getDescription(TestExecutionContext.newInstance());
    assertThat(description, Matchers.containsString("--min-api 28"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testCreateDxStepForDxPseudoRuleWithUnrecognizedOutput() {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot().toPath());

    ImmutableList<Path> filesToDex =
        ImmutableList.of(Paths.get("foo.dex.jar"), Paths.get("bar.dex.jar"));
    Path outputPath = Paths.get("classes.flex");
    EnumSet<D8Step.Option> dxOptions = EnumSet.noneOf(D8Step.Option.class);
    SmartDexingStep.createDxStepForDxPseudoRule(
        androidPlatformTarget,
        new ImmutableList.Builder<>(),
        FakeBuildContext.NOOP_CONTEXT,
        filesystem,
        filesToDex,
        outputPath,
        dxOptions,
        Optional.empty(),
        XzStep.DEFAULT_COMPRESSION_LEVEL,
        D8Step.D8,
        null,
        false,
        Optional.empty());
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.dalvik.ZipSplitter;
import com.facebook.buck.android.proguard.ProguardTranslatorFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SplitZipStepTest {
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  @Test
  public void testMetaList() throws IOException {
    Path outJar = tempDir.newFile("test.jar").toPath();
    Map<String, String> fileToClassName;
    try (ZipOutputStream zipOut =
        new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outJar)))) {
      fileToClassName =
          ImmutableMap.of(
              "com/facebook/foo.class", "com.facebook.foo",
              "bar.class", "bar");
      for (String entry : fileToClassName.keySet()) {
        zipOut.putNextEntry(new ZipEntry(entry));
        zipOut.write(new byte[] {0});
      }
    }

    StringWriter stringWriter = new StringWriter();
    try (BufferedWriter writer = new BufferedWriter(stringWriter)) {
      ImmutableSet<APKModule> requires = ImmutableSet.of();
      SplitZipStep.writeMetaList(
          writer,
          APKModule.of(SplitZipStep.SECONDARY_DEX_ID),
          requires,
          ImmutableList.of(outJar),
          DexStore.JAR);
    }
    List<String> lines = CharStreams.readLines(new StringReader(stringWriter.toString()));
    assertEquals(1, lines.size());

    String line = Iterables.getLast(lines, null);
    String[] data = line.split(" ");
    assertEquals(3, data.length);

    // Note that we cannot test data[1] (the hash) because zip files change their hash each
    // time they are written due to timestamps written into the file.
    assertEquals("secondary-1.dex.jar", data[0]);
    assertTrue(
        String.format("Unexpected class: %s", data[2]), fileToClassName.containsValue(data[2]));
  }

  @Test
  public void testMetaListApkModuule() throws IOException {
    Path outJar = tempDir.newFile("test.jar").toPath();
    Map<String, String> fileToClassName;
    try (ZipOutputStream zipOut =
        new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outJar)))) {
      fileToClassName =
          ImmutableMap.of(
              "com/facebook/foo.class", "com.facebook.foo",
              "bar.class", "bar");
      for (String entry : fileToClassName.keySet()) {
        zipOut.putNextEntry(new ZipEntry(entry));
        zipOut.write(new byte[] {0});
      }
    }

    StringWriter stringWriter = new StringWriter();
    try (BufferedWriter writer = new BufferedWriter(stringWriter)) {
      ImmutableSet<APKModule> requires = ImmutableSet.of(APKModule.of("dependency"));
      SplitZipStep.writeMetaList(
          writer,
          APKModule.of("some_module_name"),
          requires,
          ImmutableList.of(outJar),
          DexStore.JAR);
    }
    List<String> lines = CharStreams.readLines(new StringReader(stringWriter.toString()));
    assertEquals(3, lines.size());

    assertEquals(lines.get(0), ".id some_module_name");
    assertEquals(lines.get(1), ".requires dependency");

    String line = Iterables.getLast(lines, null);
    String[] data = line.split(" ");
    assertEquals(3, data.length);

    // Note that we cannot test data[1] (the hash) because zip files change their hash each
    // time they are written due to timestamps written into the file.
    assertEquals("secondary-1.dex.jar", data[0]);
    assertTrue(
        String.format("Unexpected class: %s", data[2]), fileToClassName.containsValue(data[2]));
  }

  @Test
  public void testRequiredInPrimaryZipPredicate() throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    SplitZipStep splitZipStep =
        new SplitZipStep(
            projectFilesystem,
            /* inputPathsToSplit */ ImmutableSet.of(),
            /* secondaryJarMetaPath */ Paths.get(""),
            /* primaryJarPath */ Paths.get(""),
            /* secondaryJarDir */ Paths.get(""),
            /* secondaryJarPattern */ "",
            /* additionalDexStoreJarMetaPath */ Paths.get(""),
            /* additionalDexStoreJarDir */ Paths.get(""),
            /* proguardFullConfigFile */ Optional.empty(),
            /* proguardMappingFile */ Optional.empty(),
            false,
            new DexSplitMode(
                /* shouldSplitDex */ true,
                ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
                DexStore.JAR,
                /* linearAllocHardLimit */ 4 * 1024 * 1024,
                /* primaryDexPatterns */ ImmutableSet.of("List"),
                /* allowRDotJavaInSecondaryDex */ false),
            /* additionalDexStoreToJarPathMap */ ImmutableMultimap.of(),
            ImmutableSortedMap.of(),
            null,
            Paths.get(""));

    Predicate<String> requiredInPrimaryZipPredicate =
        splitZipStep.createRequiredInPrimaryZipPredicate(
            ProguardTranslatorFactory.createForTest(Optional.empty()));
    assertFalse(
        "com/google/common/collect/ImmutableSortedSet.class is not even mentioned.",
        requiredInPrimaryZipPredicate.test("com/google/common/collect/ImmutableSortedSet.class"));
    assertFalse(
        "com/google/common/collect/ImmutableSet.class is not even mentioned.",
        requiredInPrimaryZipPredicate.test("com/google/common/collect/ImmutableSet.class"));
    assertFalse(
        "com/google/common/collect/ImmutableSet.class cannot have whitespace as param.",
        requiredInPrimaryZipPredicate.test("  com/google/common/collect/ImmutableSet.class"));
    assertFalse(
        "com/google/common/collect/ImmutableMap.class is not even mentioned.",
        requiredInPrimaryZipPredicate.test("com/google/common/collect/ImmutableMap.class"));
    assertFalse(
        "com/google/common/collect/Iterables.class is not even mentioned.",
        requiredInPrimaryZipPredicate.test("com/google/common/collect/Iterables.class"));
    assertTrue(
        "java/awt/List.class matches the substring 'List'.",
        requiredInPrimaryZipPredicate.test("java/awt/List.class"));
    assertFalse(
        "Substring matching is case-sensitive.",
        requiredInPrimaryZipPredicate.test("shiny/Glistener.class"));
  }

  @Test
  public void testRequiredInPrimaryZipPredicateWithProguard() throws IOException {
    List<String> linesInMappingFile =
        ImmutableList.of(
            "foo.bar.MappedPrimary -> foo.bar.a:",
            "foo.bar.MappedSecondary -> foo.bar.b:",
            "foo.bar.UnmappedPrimary -> foo.bar.UnmappedPrimary:",
            "foo.bar.UnmappedSecondary -> foo.bar.UnmappedSecondary:",
            "foo.primary.MappedPackage -> x.a:",
            "foo.secondary.MappedPackage -> x.b:",
            "foo.primary.UnmappedPackage -> foo.primary.UnmappedPackage:");

    ImmutableSet<String> primaryDexPatterns =
        ImmutableSet.of(
            "/primary/",
            "x/",
            // Actual primary dex classes.
            "foo/bar/MappedPrimary",
            "foo/bar/UnmappedPrimary",
            // Red herrings!
            "foo/bar/b",
            "x/b");

    Path proguardConfigFile = tempDir.newFile("configuration.txt").toPath();
    Path proguardMappingFile = tempDir.newFile("mapping.txt").toPath();
    Files.write(proguardConfigFile, ImmutableList.of());
    Files.write(proguardMappingFile, linesInMappingFile);

    SplitZipStep splitZipStep =
        new SplitZipStep(
            new FakeProjectFilesystem(),
            /* inputPathsToSplit */ ImmutableSet.of(),
            /* secondaryJarMetaPath */ Paths.get(""),
            /* primaryJarPath */ Paths.get(""),
            /* secondaryJarDir */ Paths.get(""),
            /* secondaryJarPattern */ "",
            /* additionalDexStoreJarMetaPath */ Paths.get(""),
            /* additionalDexStoreJarDir */ Paths.get(""),
            /* proguardFullConfigFile */ Optional.of(proguardConfigFile),
            /* proguardMappingFile */ Optional.of(proguardMappingFile),
            false,
            new DexSplitMode(
                /* shouldSplitDex */ true,
                ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
                DexStore.JAR,
                /* linearAllocHardLimit */ 4 * 1024 * 1024,
                primaryDexPatterns,
                /* allowRDotJavaInSecondaryDex */ false),
            /* additionalDexStoreToJarPathMap */ ImmutableMultimap.of(),
            /* pathToReportDir */
            ImmutableSortedMap.of(),
            null,
            Paths.get(""));

    ProguardTranslatorFactory translatorFactory =
        ProguardTranslatorFactory.create(
            Optional.of(proguardConfigFile), Optional.of(proguardMappingFile), false);

    Predicate<String> requiredInPrimaryZipPredicate =
        splitZipStep.createRequiredInPrimaryZipPredicate(translatorFactory);
    assertTrue(
        "Mapped class from primary list should be in primary.",
        requiredInPrimaryZipPredicate.test("foo/bar/a.class"));
    assertTrue(
        "Unmapped class from primary list should be in primary.",
        requiredInPrimaryZipPredicate.test("foo/bar/UnmappedPrimary.class"));
    assertTrue(
        "Mapped class from substring should be in primary.",
        requiredInPrimaryZipPredicate.test("x/a.class"));
    assertTrue(
        "Unmapped class from substring should be in primary.",
        requiredInPrimaryZipPredicate.test("foo/primary/UnmappedPackage.class"));
    assertFalse(
        "Mapped class with obfuscated name match should not be in primary.",
        requiredInPrimaryZipPredicate.test("foo/bar/b.class"));
    assertFalse(
        "Unmapped class name should not randomly be in primary.",
        requiredInPrimaryZipPredicate.test("foo/bar/UnmappedSecondary.class"));
    assertFalse(
        "Map class with obfuscated name substring should not be in primary.",
        requiredInPrimaryZipPredicate.test("x/b.class"));
  }

  @Test
  public void testNonObfuscatedBuild() throws IOException {
    Path proguardConfigFile = tempDir.newFile("configuration.txt").toPath();
    Path proguardMappingFile = tempDir.newFile("mapping.txt").toPath();

    Files.write(proguardConfigFile, ImmutableList.of("-dontobfuscate"));

    SplitZipStep splitZipStep =
        new SplitZipStep(
            new FakeProjectFilesystem(),
            /* inputPathsToSplit */ ImmutableSet.of(),
            /* secondaryJarMetaPath */ Paths.get(""),
            /* primaryJarPath */ Paths.get(""),
            /* secondaryJarDir */ Paths.get(""),
            /* secondaryJarPattern */ "",
            /* additionalDexStoreJarMetaPath */ Paths.get(""),
            /* additionalDexStoreJarDir */ Paths.get(""),
            /* proguardFullConfigFile */ Optional.of(proguardConfigFile),
            /* proguardMappingFile */ Optional.of(proguardMappingFile),
            false,
            new DexSplitMode(
                /* shouldSplitDex */ true,
                ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
                DexStore.JAR,
                /* linearAllocHardLimit */ 4 * 1024 * 1024,
                /* primaryDexPatterns */ ImmutableSet.of("primary"),
                /* allowRDotJavaInSecondaryDex */ false),
            /* additionalDexStoreToJarPathMap */ ImmutableMultimap.of(),
            /* pathToReportDir */
            ImmutableSortedMap.of(),
            null,
            Paths.get(""));

    ProguardTranslatorFactory translatorFactory =
        ProguardTranslatorFactory.create(
            Optional.of(proguardConfigFile), Optional.of(proguardMappingFile), false);

    Predicate<String> requiredInPrimaryZipPredicate =
        splitZipStep.createRequiredInPrimaryZipPredicate(translatorFactory);
    assertTrue(
        "Primary class should be in primary.", requiredInPrimaryZipPredicate.test("primary.class"));
    assertFalse(
        "Secondary class should be in secondary.",
        requiredInPrimaryZipPredicate.test("secondary.class"));
  }

  @Test
  public void testClassFilePattern() {
    assertTrue(
        SplitZipStep.CLASS_FILE_PATTERN
            .matcher("com/facebook/orca/threads/ParticipantInfo.class")
            .matches());
    assertTrue(
        SplitZipStep.CLASS_FILE_PATTERN
            .matcher("com/facebook/orca/threads/ParticipantInfo$1.class")
            .matches());
  }
}

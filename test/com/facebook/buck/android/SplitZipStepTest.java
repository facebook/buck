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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;

import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.tree.ClassNode;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SplitZipStepTest {
  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Test
  public void testMetaList() throws IOException {
    Path outJar = tempDir.newFile("test.jar").toPath();
    ZipOutputStream zipOut = new ZipOutputStream(
        new BufferedOutputStream(Files.newOutputStream(outJar)));
    Map<String, String> fileToClassName = ImmutableMap.of(
        "com/facebook/foo.class", "com.facebook.foo",
        "bar.class", "bar");
    try {
      for (String entry : fileToClassName.keySet()) {
        zipOut.putNextEntry(new ZipEntry(entry));
        zipOut.write(new byte[] { 0 });
      }
    } finally {
      zipOut.close();
    }

    StringWriter stringWriter = new StringWriter();
    BufferedWriter writer = new BufferedWriter(stringWriter);
    try {
      SplitZipStep.writeMetaList(writer, ImmutableList.of(outJar), DexStore.JAR);
    } finally {
      writer.close();
    }
    List<String> lines = CharStreams.readLines(new StringReader(stringWriter.toString()));
    assertEquals(1, lines.size());

    String line = Iterables.getFirst(lines, null);
    String[] data = line.split(" ");
    assertEquals(3, data.length);

    // Note that we cannot test data[1] (the hash) because zip files change their hash each
    // time they are written due to timestamps written into the file.
    assertEquals("secondary-1.dex.jar", data[0]);
    assertTrue(String.format("Unexpected class: %s", data[2]),
        fileToClassName.values().contains(data[2]));
  }

  @Test
  public void testRequiredInPrimaryZipPredicate() throws IOException {
    Path primaryDexClassesFile = Paths.get("the/manifest.txt");
    List<String> linesInManifestFile = ImmutableList.of(
        "com/google/common/collect/ImmutableSortedSet",
        "  com/google/common/collect/ImmutableSet",
        "# com/google/common/collect/ImmutableMap");
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.readLines(primaryDexClassesFile))
        .andReturn(linesInManifestFile);

    SplitZipStep splitZipStep = new SplitZipStep(
        projectFilesystem,
        /* inputPathsToSplit */ ImmutableSet.<Path>of(),
        /* secondaryJarMetaPath */ Paths.get(""),
        /* primaryJarPath */ Paths.get(""),
        /* secondaryJarDir */ Paths.get(""),
        /* secondaryJarPattern */ "",
        /* proguardFullConfigFile */ Optional.<Path>absent(),
        /* proguardMappingFile */ Optional.<Path>absent(),
        new DexSplitMode(
            /* shouldSplitDex */ true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR,
            /* useLinearAllocSplitDex */ true,
            /* linearAllocHardLimit */ 4 * 1024 * 1024,
            /* primaryDexPatterns */ ImmutableSet.of("List"),
            Optional.<SourcePath>of(new FakeSourcePath("the/manifest.txt")),
            /* primaryDexScenarioFile */ Optional.<SourcePath>absent(),
            /* isPrimaryDexScenarioOverflowAllowed */ false,
            /* secondaryDexHeadClassesFile */ Optional.<SourcePath>absent(),
            /* secondaryDexTailClassesFile */ Optional.<SourcePath>absent()),
        Optional.<Path>absent(),
        Optional.of(Paths.get("the/manifest.txt")),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        /* pathToReportDir */ Paths.get(""));

    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    EasyMock.replay(projectFilesystem, context);

    Predicate<String> requiredInPrimaryZipPredicate = splitZipStep
        .createRequiredInPrimaryZipPredicate(
            ProguardTranslatorFactory.createForTest(Optional.<Map<String, String>>absent()),
            Suppliers.ofInstance(ImmutableList.<ClassNode>of()));
    assertTrue(
        "All non-.class files should be accepted.",
        requiredInPrimaryZipPredicate.apply("apples.txt"));
    assertTrue(
        "com/google/common/collect/ImmutableSortedSet.class is listed in the manifest verbatim.",
        requiredInPrimaryZipPredicate.apply("com/google/common/collect/ImmutableSortedSet.class"));
    assertTrue(
        "com/google/common/collect/ImmutableSet.class is in the manifest with whitespace.",
        requiredInPrimaryZipPredicate.apply("com/google/common/collect/ImmutableSet.class"));
    assertFalse(
        "com/google/common/collect/ImmutableSet.class cannot have whitespace as param.",
        requiredInPrimaryZipPredicate.apply("  com/google/common/collect/ImmutableSet.class"));
    assertFalse(
        "com/google/common/collect/ImmutableMap.class is commented out.",
        requiredInPrimaryZipPredicate.apply("com/google/common/collect/ImmutableMap.class"));
    assertFalse(
        "com/google/common/collect/Iterables.class is not even mentioned.",
        requiredInPrimaryZipPredicate.apply("com/google/common/collect/Iterables.class"));
    assertTrue(
        "java/awt/List.class matches the substring 'List'.",
        requiredInPrimaryZipPredicate.apply("java/awt/List.class"));
    assertFalse(
        "Substring matching is case-sensitive.",
        requiredInPrimaryZipPredicate.apply("shiny/Glistener.class"));

    EasyMock.verify(projectFilesystem, context);
  }

  @Test
  public void testRequiredInPrimaryZipPredicateWithProguard() throws IOException {
    List<String> linesInMappingFile = ImmutableList.of(
        "foo.bar.MappedPrimary -> foo.bar.a:",
        "foo.bar.MappedSecondary -> foo.bar.b:",
        "foo.bar.UnmappedPrimary -> foo.bar.UnmappedPrimary:",
        "foo.bar.UnmappedSecondary -> foo.bar.UnmappedSecondary:",
        "foo.primary.MappedPackage -> x.a:",
        "foo.secondary.MappedPackage -> x.b:",
        "foo.primary.UnmappedPackage -> foo.primary.UnmappedPackage:");
    List<String> linesInManifestFile = ImmutableList.of(
        // Actual primary dex classes.
        "foo/bar/MappedPrimary",
        "foo/bar/UnmappedPrimary",
        // Red herrings!
        "foo/bar/b",
        "x/b");

    Path proguardConfigFile = Paths.get("the/configuration.txt");
    Path proguardMappingFile = Paths.get("the/mapping.txt");
    Path primaryDexClassesFile = Paths.get("the/manifest.txt");

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.readLines(primaryDexClassesFile))
        .andReturn(linesInManifestFile);
    EasyMock.expect(projectFilesystem.readLines(proguardConfigFile))
        .andReturn(ImmutableList.<String>of());
    EasyMock.expect(projectFilesystem.readLines(proguardMappingFile))
        .andReturn(linesInMappingFile);

    SplitZipStep splitZipStep = new SplitZipStep(
        projectFilesystem,
        /* inputPathsToSplit */ ImmutableSet.<Path>of(),
        /* secondaryJarMetaPath */ Paths.get(""),
        /* primaryJarPath */ Paths.get(""),
        /* secondaryJarDir */ Paths.get(""),
        /* secondaryJarPattern */ "",
        /* proguardFullConfigFile */ Optional.of(proguardConfigFile),
        /* proguardMappingFile */ Optional.of(proguardMappingFile),
        new DexSplitMode(
            /* shouldSplitDex */ true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR,
            /* useLinearAllocSplitDex */ true,
            /* linearAllocHardLimit */ 4 * 1024 * 1024,
            /* primaryDexPatterns */ ImmutableSet.of("/primary/", "x/"),
            Optional.<SourcePath>of(new FakeSourcePath("the/manifest.txt")),
            /* primaryDexScenarioFile */ Optional.<SourcePath>absent(),
            /* isPrimaryDexScenarioOverflowAllowed */ false,
            /* secondaryDexHeadClassesFile */ Optional.<SourcePath>absent(),
            /* secondaryDexTailClassesFile */ Optional.<SourcePath>absent()),
        Optional.<Path>absent(),
        Optional.of(Paths.get("the/manifest.txt")),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        /* pathToReportDir */ Paths.get(""));

    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    EasyMock.replay(projectFilesystem, context);

    ProguardTranslatorFactory translatorFactory = ProguardTranslatorFactory.create(
        projectFilesystem,
        Optional.of(proguardConfigFile), Optional.of(proguardMappingFile));

    Predicate<String> requiredInPrimaryZipPredicate = splitZipStep
        .createRequiredInPrimaryZipPredicate(
            translatorFactory,
            Suppliers.ofInstance(ImmutableList.<ClassNode>of()));
    assertTrue(
        "Mapped class from primary list should be in primary.",
        requiredInPrimaryZipPredicate.apply("foo/bar/a.class"));
    assertTrue(
        "Unmapped class from primary list should be in primary.",
        requiredInPrimaryZipPredicate.apply("foo/bar/UnmappedPrimary.class"));
    assertTrue(
        "Mapped class from substring should be in primary.",
        requiredInPrimaryZipPredicate.apply("x/a.class"));
    assertTrue(
        "Unmapped class from substring should be in primary.",
        requiredInPrimaryZipPredicate.apply("foo/primary/UnmappedPackage.class"));
    assertFalse(
        "Mapped class with obfuscated name match should not be in primary.",
        requiredInPrimaryZipPredicate.apply("foo/bar/b.class"));
    assertFalse(
        "Unmapped class name should not randomly be in primary.",
        requiredInPrimaryZipPredicate.apply("foo/bar/UnmappedSecondary.class"));
    assertFalse(
        "Map class with obfuscated name substring should not be in primary.",
        requiredInPrimaryZipPredicate.apply("x/b.class"));

    EasyMock.verify(projectFilesystem, context);
  }

  @Test
  public void testNonObfuscatedBuild() throws IOException {
    Path proguardConfigFile = Paths.get("the/configuration.txt");
    Path proguardMappingFile = Paths.get("the/mapping.txt");

    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem.readLines(proguardConfigFile))
        .andReturn(ImmutableList.of("-dontobfuscate"));

    SplitZipStep splitZipStep = new SplitZipStep(
        projectFilesystem,
        /* inputPathsToSplit */ ImmutableSet.<Path>of(),
        /* secondaryJarMetaPath */ Paths.get(""),
        /* primaryJarPath */ Paths.get(""),
        /* secondaryJarDir */ Paths.get(""),
        /* secondaryJarPattern */ "",
        /* proguardFullConfigFile */ Optional.of(proguardConfigFile),
        /* proguardMappingFile */ Optional.of(proguardMappingFile),
        new DexSplitMode(
            /* shouldSplitDex */ true,
            ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
            DexStore.JAR,
            /* useLinearAllocSplitDex */ true,
            /* linearAllocHardLimit */ 4 * 1024 * 1024,
            /* primaryDexPatterns */ ImmutableSet.of("primary"),
            /* primaryDexClassesFile */ Optional.<SourcePath>absent(),
            /* primaryDexScenarioFile */ Optional.<SourcePath>absent(),
            /* isPrimaryDexScenarioOverflowAllowed */ false,
            /* secondaryDexHeadClassesFile */ Optional.<SourcePath>absent(),
            /* secondaryDexTailClassesFile */ Optional.<SourcePath>absent()),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        Optional.<Path>absent(),
        /* pathToReportDir */ Paths.get(""));

    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    EasyMock.replay(projectFilesystem, context);

    ProguardTranslatorFactory translatorFactory = ProguardTranslatorFactory.create(
        projectFilesystem,
        Optional.of(proguardConfigFile), Optional.of(proguardMappingFile));

    Predicate<String> requiredInPrimaryZipPredicate = splitZipStep
        .createRequiredInPrimaryZipPredicate(
            translatorFactory,
            Suppliers.ofInstance(ImmutableList.<ClassNode>of()));
    assertTrue(
        "Primary class should be in primary.",
        requiredInPrimaryZipPredicate.apply("primary.class"));
    assertFalse(
        "Secondary class should be in secondary.",
        requiredInPrimaryZipPredicate.apply("secondary.class"));

    EasyMock.verify(projectFilesystem, context);
  }

  @Test
  public void testClassFilePattern() {
    assertTrue(SplitZipStep.CLASS_FILE_PATTERN.matcher(
        "com/facebook/orca/threads/ParticipantInfo.class").matches());
    assertTrue(SplitZipStep.CLASS_FILE_PATTERN.matcher(
        "com/facebook/orca/threads/ParticipantInfo$1.class").matches());
  }
}

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

package com.facebook.buck.dalvik;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class DefaultZipSplitterTest {

  @Rule
  public TemporaryPaths tmpDir = new TemporaryPaths();

  private Path testInZip;
  private Set<Path> testInZips;
  private Path outPrimary;
  private String secondaryPattern;
  private Predicate<String> processor = new Predicate<String>() {
    @Override
    public boolean apply(String name) {
      return name.startsWith("primary");
    }
  };

  @Before
  public void setUp() throws Exception {
    byte[] fakeData = {0, 0, 0, 0};

    // Map of output file => [ zip entry, ... ]
    Multimap<Path, String> outputToInputMap = LinkedHashMultimap.create();

    // Uber in zip for tests.
    testInZip = tmpDir.getRoot().resolve("in.zip");
    outputToInputMap.putAll(testInZip, ImmutableList.of(
        "secondary-1",
        "secondary-2",
        "secondary-3",
        "primary",
        "secondary-4"));

    // Tests with multiple input zips.
    testInZips = Sets.newLinkedHashSet();
    Path inA = tmpDir.getRoot().resolve("in-a.zip");
    testInZips.add(inA);
    outputToInputMap.putAll(inA, ImmutableList.of(
        "primary-a-1",
        "primary-a-2",
        "primary-a-3",
        "secondary-a-1",
        "secondary-a-2",
        "secondary-a-3"));
    Path inB = tmpDir.getRoot().resolve("in-b.zip");
    testInZips.add(inB);
    outputToInputMap.putAll(inB, ImmutableList.of(
        "secondary-b-1",
        "secondary-b-2"));
    Path inC = tmpDir.getRoot().resolve("in-c.zip");
    testInZips.add(inC);
    outputToInputMap.putAll(inC, ImmutableList.of(
        "secondary-c-1",
        "secondary-c-2",
        "secondary-c-3"));

    // Write the output files.
    for (Path outputFile : outputToInputMap.keySet()) {
      ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(outputFile));
      for (String name : outputToInputMap.get(outputFile)) {
        zipOut.putNextEntry(new ZipEntry(name));
        zipOut.write(fakeData);
      }
      zipOut.close();
    }

    outPrimary = tmpDir.getRoot().resolve("primary.zip");
    secondaryPattern = "secondary-%d.zip";
  }

  @Test
  public void testBigLimit() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        Collections.singleton(testInZip),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        999 /* soft limit */,
        999 /* hard limit */,
        processor,
        ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.DONT_INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(primaryZipContains("primary"));
    assertTrue(primaryZipContains("secondary-1"));
    assertTrue(primaryZipContains("secondary-2"));
    assertTrue(primaryZipContains("secondary-3"));
    assertTrue(primaryZipContains("secondary-4"));
  }

  @Test
  public void testMediumLimit() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        Collections.singleton(testInZip),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        12 /* soft limit */,
        12 /* hard limit */,
        processor,
        ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.DONT_INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(primaryZipContains("primary"));
    assertTrue(primaryZipContains("secondary-3"));
    assertTrue(primaryZipContains("secondary-4"));
    assertTrue(nthSecondaryZipContains(1, "secondary-1"));
    assertTrue(nthSecondaryZipContains(1, "secondary-2"));
  }

  @Test
  public void testSmallLimit() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        Collections.singleton(testInZip),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        8 /* soft limit */,
        8 /* hard limit */,
        processor,
        ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.DONT_INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(primaryZipContains("primary"));
    assertTrue(primaryZipContains("secondary-4"));
    assertTrue(nthSecondaryZipContains(1, "secondary-1"));
    assertTrue(nthSecondaryZipContains(1, "secondary-2"));
    assertTrue(nthSecondaryZipContains(2, "secondary-3"));
  }

  @Test
  public void testBigLimitMinimizePrimaryZip() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        Collections.singleton(testInZip),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        999 /* soft limit */,
        999,
        processor,
        ZipSplitter.DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.DONT_INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(primaryZipContains("primary"));
    assertTrue(nthSecondaryZipContains(1, "secondary-1"));
    assertTrue(nthSecondaryZipContains(1, "secondary-2"));
    assertTrue(nthSecondaryZipContains(1, "secondary-3"));
    assertTrue(nthSecondaryZipContains(1, "secondary-4"));
  }

  @Test
  public void testMediumLimitMinimizePrimaryZip() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        Collections.singleton(testInZip),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        12 /* soft limit */,
        12,
        processor,
        ZipSplitter.DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.DONT_INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(primaryZipContains("primary"));
    assertTrue(nthSecondaryZipContains(1, "secondary-1"));
    assertTrue(nthSecondaryZipContains(1, "secondary-2"));
    assertTrue(nthSecondaryZipContains(1, "secondary-3"));
    assertTrue(nthSecondaryZipContains(2, "secondary-4"));
  }

  @Test
  public void testSmallLimitMinimizePrimaryZip() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        Collections.singleton(testInZip),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        8 /* soft limit */,
        8,
        processor,
        ZipSplitter.DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.DONT_INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(primaryZipContains("primary"));
    assertTrue(nthSecondaryZipContains(1, "secondary-1"));
    assertTrue(nthSecondaryZipContains(1, "secondary-2"));
    assertTrue(nthSecondaryZipContains(2, "secondary-3"));
    assertTrue(nthSecondaryZipContains(2, "secondary-4"));
  }

  @Test
  public void testSoftLimit() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        ImmutableSet.copyOf(testInZips),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        8 /* soft limit */,
        12 /* hard limit */,
        processor,
        ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.DONT_INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(primaryZipContains("primary-a-1"));
    assertTrue(primaryZipContains("primary-a-2"));
    assertTrue(primaryZipContains("primary-a-3"));
    assertTrue(nthSecondaryZipContains(1, "secondary-a-1"));
    assertTrue(nthSecondaryZipContains(1, "secondary-a-2"));
    assertTrue(nthSecondaryZipContains(1, "secondary-a-3"));
    assertTrue(nthSecondaryZipContains(2, "secondary-b-1"));
    assertTrue(nthSecondaryZipContains(2, "secondary-b-2"));
    assertTrue(nthSecondaryZipContains(3, "secondary-c-1"));
    assertTrue(nthSecondaryZipContains(3, "secondary-c-2"));
    assertTrue(nthSecondaryZipContains(3, "secondary-c-3"));
  }

  @Test
  public void testCanary() throws IOException {
    DefaultZipSplitter.splitZip(
        new ProjectFilesystem(tmpDir.getRoot()),
        Collections.singleton(testInZip),
        outPrimary,
        tmpDir.getRoot(),
        secondaryPattern,
        80 /* soft limit */,
        80,
        processor,
        ZipSplitter.DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE,
        ZipSplitter.CanaryStrategy.INCLUDE_CANARIES,
        tmpDir.newFolder("report"))
        .execute();
    assertTrue(nthSecondaryZipContains(1, "secondary/dex01/Canary.class"));
    assertTrue(nthSecondaryZipContains(2, "secondary/dex02/Canary.class"));
    assertTrue(nthSecondaryZipContains(3, "secondary/dex03/Canary.class"));
    assertTrue(nthSecondaryZipContains(4, "secondary/dex04/Canary.class"));
  }

  private boolean primaryZipContains(String name) throws IOException {
    return zipContains(outPrimary, name);
  }

  private boolean nthSecondaryZipContains(int index, String name) throws IOException {
    String zipName = String.format(secondaryPattern, index);
    return zipContains(tmpDir.getRoot().resolve(zipName), name);
  }

  private static boolean zipContains(Path file, String name) throws IOException {
    ZipFile zip = new ZipFile(file.toFile());
    try {
      return zip.getEntry(name) != null;
    } finally {
      zip.close();
    }
  }
}

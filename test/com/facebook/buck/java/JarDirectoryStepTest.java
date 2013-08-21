/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public class JarDirectoryStepTest {

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void shouldNotThrowAnExceptionWhenAddingDuplicateEntries() throws IOException {
    File zipup = folder.newFolder("zipup");

    File first = createZip(new File(zipup, "a.zip"), "example.txt");
    File second = createZip(new File(zipup, "b.zip"), "example.txt");

    JarDirectoryStep step = new JarDirectoryStep("output.jar",
        ImmutableSet.of(first.getName(), second.getName()),
        "com.example.Main",
        /* manifest file */ null);
    ExecutionContext context = ExecutionContext.builder()
        .setProjectFilesystem(new ProjectFilesystem(zipup))
        .setConsole(new TestConsole())
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.detect())
        .build();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    File zip = new File(zipup, "output.jar");
    assertTrue(zip.exists());

    // "example.txt" and the MANIFEST.MF.
    assertZipFileCountIs(2, zip);
    assertZipContains(zip, "example.txt");
  }

  @Test
  public void shouldNotComplainWhenDuplicateDirectoryNamesAreAdded() throws IOException {
    File zipup = folder.newFolder();

    File first = createZip(new File(zipup, "first.zip"), "dir/example.txt", "dir/root1file.txt");
    File second = createZip(new File(zipup, "second.zip"), "dir/example.txt", "dir/root2file.txt");

    JarDirectoryStep step = new JarDirectoryStep("output.jar",
        ImmutableSet.of(first.getName(), second.getName()),
        "com.example.Main",
        /* manifest file */ null);
    ExecutionContext context = ExecutionContext.builder()
        .setProjectFilesystem(new ProjectFilesystem(zipup))
        .setConsole(new TestConsole())
        .setEventBus(BuckEventBusFactory.newInstance())
        .setPlatform(Platform.detect())
        .build();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    File zip = new File(zipup, "output.jar");

    // The three below plus the manifest.
    assertZipFileCountIs(4, zip);
    assertZipContains(zip, "dir/example.txt", "dir/root1file.txt", "dir/root2file.txt");
  }

  private File createZip(File zipFile, String... fileNames) throws IOException {
    try (Zip zip = new Zip(zipFile, true)) {
      for (String fileName : fileNames) {
        zip.add(fileName, "");
      }
    }
    return zipFile;
  }

  private void assertZipFileCountIs(int expected, File zip) throws IOException {
    Set<String> fileNames = getFileNames(zip);

    assertEquals(fileNames.toString(), expected, fileNames.size());
  }

  private void assertZipContains(File zip, String... files) throws IOException {
    final Set<String> contents = getFileNames(zip);

    for (String file : files) {
      assertTrue(String.format("%s -> %s", file, contents), contents.contains(file));
    }
  }

  private Set<String> getFileNames(File zipFile) throws IOException {
    try (Zip zip = new Zip(zipFile, false)) {
      return zip.getFileNames();
    }
  }

}

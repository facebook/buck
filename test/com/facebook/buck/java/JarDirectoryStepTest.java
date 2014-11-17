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

import static java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION;
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JarDirectoryStepTest {

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void shouldNotThrowAnExceptionWhenAddingDuplicateEntries() throws IOException {
    File zipup = folder.newFolder("zipup");

    File first = createZip(new File(zipup, "a.zip"), "example.txt");
    File second = createZip(new File(zipup, "b.zip"), "example.txt");

    JarDirectoryStep step = new JarDirectoryStep(Paths.get("output.jar"),
        ImmutableSet.of(Paths.get(first.getName()), Paths.get(second.getName())),
        "com.example.Main",
        /* manifest file */ null);
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(zipup.toPath()))
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

    JarDirectoryStep step = new JarDirectoryStep(Paths.get("output.jar"),
        ImmutableSet.of(Paths.get(first.getName()), Paths.get(second.getName())),
        "com.example.Main",
        /* manifest file */ null);

    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(zipup.toPath()))
        .build();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    File zip = new File(zipup, "output.jar");

    // The three below plus the manifest.
    assertZipFileCountIs(4, zip);
    assertZipContains(zip, "dir/example.txt", "dir/root1file.txt", "dir/root2file.txt");
  }

  @Test
  public void entriesFromTheGivenManifestShouldOverrideThoseInTheJars() throws IOException {
    String expected = "1.4";
    // Write the manifest, setting the implementation version
    File tmp = folder.newFolder();

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), expected);
    File manifestFile = new File(tmp, "manifest");
    try (FileOutputStream fos = new FileOutputStream(manifestFile)) {
      manifest.write(fos);
    }

    // Write another manifest, setting the implementation version to something else
    manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), "1.0");

    File input = new File(tmp, "input.jar");
    try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(input)) {
      ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
      out.putNextEntry(entry);
      manifest.write(out);
    }

    File output = new File(tmp, "output.jar");
    JarDirectoryStep step = new JarDirectoryStep(
        Paths.get("output.jar"),
        ImmutableSet.of(Paths.get("input.jar")),
        /* main class */ null,
        Paths.get("manifest"),
        /* merge manifest */ true,
        /* blacklist */ ImmutableSet.<String>of());
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(tmp.toPath()))
        .build();
    assertEquals(0, step.execute(context));

    try (Zip zip = new Zip(output, false)) {
      byte[] rawManifest = zip.readFully("META-INF/MANIFEST.MF");
      manifest = new Manifest(new ByteArrayInputStream(rawManifest));
      String version = manifest.getMainAttributes().getValue(IMPLEMENTATION_VERSION);

      assertEquals(expected, version);
    }
  }

  @Test
  public void jarsShouldContainDirectoryEntries() throws IOException {
    File zipup = folder.newFolder("dir-zip");

    File subdir = new File(zipup, "dir/subdir");
    assertTrue(subdir.mkdirs());
    new File(subdir, "a.txt");
    Files.write(subdir.toPath().resolve("a.txt"), "cake".getBytes());

    JarDirectoryStep step = new JarDirectoryStep(Paths.get("output.jar"),
        ImmutableSet.of(zipup.toPath()),
        /* main class */ null,
        /* manifest file */ null);
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(zipup.toPath()))
        .build();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    File zip = new File(zipup, "output.jar");
    assertTrue(zip.exists());

    // Iterate over each of the entries, expecting to see the directory names as entries.
    Set<String> expected = Sets.newHashSet("dir/", "dir/subdir/");
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(zip))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        expected.remove(entry.getName());
      }
    }
    assertTrue("Didn't see entries for: " + expected, expected.isEmpty());
  }

  @Test
  public void shouldNotMergeManifestsIfRequested() throws IOException {
    Manifest fromJar = createManifestWithExampleSection(ImmutableMap.of("Not-Seen", "ever"));
    Manifest fromUser = createManifestWithExampleSection(ImmutableMap.of("cake", "cheese"));

    Manifest seenManifest = jarDirectoryAndReadManifest(fromJar, fromUser, false);

    assertEquals(fromUser.getEntries(), seenManifest.getEntries());
  }

  @Test
  public void shouldMergeManifestsIfAsked() throws IOException {
    Manifest fromJar = createManifestWithExampleSection(ImmutableMap.of("Not-Seen", "ever"));
    Manifest fromUser = createManifestWithExampleSection(ImmutableMap.of("cake", "cheese"));

    Manifest seenManifest = jarDirectoryAndReadManifest(fromJar, fromUser, true);

    Manifest expectedManifest = new Manifest(fromJar);
    expectedManifest.getEntries().putAll(fromUser.getEntries());
    assertEquals(expectedManifest.getEntries(), seenManifest.getEntries());
  }

  @Test
  public void shouldNotIncludeFilesInBlacklist() throws IOException {
    File zipup = folder.newFolder();

    File first = createZip(new File(zipup, "first.zip"), "dir/file1.txt", "dir/file2.txt");

    JarDirectoryStep step = new JarDirectoryStep(Paths.get("output.jar"),
        ImmutableSet.of(Paths.get(first.getName())),
        "com.example.Main",
        /* manifest file */ null,
        /* merge manifests */ true,
        /* blacklist */ ImmutableSet.of(".*2.*"));

    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(zipup.toPath()))
        .build();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    File zip = new File(zipup, "output.jar");

    // file1.txt plus the manifest.
    assertZipFileCountIs(2, zip);
    assertZipContains(zip, "dir/file1.txt");
    assertZipDoesNotContain(zip, "dir/file2.txt");
  }

  private Manifest createManifestWithExampleSection(Map<String, String> attributes) {
    Manifest manifest = new Manifest();
    Attributes attrs = new Attributes();
    for (Map.Entry<String, String> stringStringEntry : attributes.entrySet()) {
      attrs.put(new Attributes.Name(stringStringEntry.getKey()), stringStringEntry.getValue());
    }
    manifest.getEntries().put("example", attrs);
    return manifest;
  }

  private Manifest jarDirectoryAndReadManifest(
      Manifest fromJar,
      Manifest fromUser,
      boolean mergeEntries)
      throws IOException {
    // Create a jar with a manifest we'd expect to see merged.
    File originalJar = folder.newFile("unexpected.jar");
    JarOutputStream ignored =
        new JarOutputStream(Files.newOutputStream(originalJar.toPath()), fromJar);
    ignored.close();

    // Now create the actual manifest
    File manifestFile = folder.newFile("actual_manfiest.mf");
    try (OutputStream os = Files.newOutputStream(manifestFile.toPath())) {
      fromUser.write(os);
    }

    File tmp = folder.newFolder();
    File output = new File(tmp, "example.jar");
    JarDirectoryStep step = new JarDirectoryStep(
        output.toPath(),
        ImmutableSortedSet.of(originalJar.toPath()),
        /* main class */ null,
        manifestFile.toPath(),
        mergeEntries,
        /* blacklist */ ImmutableSet.<String>of());
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(tmp.toPath()))
        .build();
    step.execute(context);

    // Now verify that the created manifest matches the expected one.
    try (JarInputStream jis = new JarInputStream(Files.newInputStream(output.toPath()))) {
      return jis.getManifest();
    }
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

  private void assertZipDoesNotContain(File zip, String... files) throws IOException {
    final Set<String> contents = getFileNames(zip);

    for (String file : files) {
      assertFalse(String.format("%s -> %s", file, contents), contents.contains(file));
    }
  }

  private Set<String> getFileNames(File zipFile) throws IOException {
    try (Zip zip = new Zip(zipFile, false)) {
      return zip.getFileNames();
    }
  }

}

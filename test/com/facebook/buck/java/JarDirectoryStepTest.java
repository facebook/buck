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
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipConstants;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JarDirectoryStepTest {

  @Rule public TemporaryPaths folder = new TemporaryPaths();

  @Test
  public void shouldNotThrowAnExceptionWhenAddingDuplicateEntries() throws IOException {
    Path zipup = folder.newFolder("zipup");

    Path first = createZip(zipup.resolve("a.zip"), "example.txt");
    Path second = createZip(zipup.resolve("b.zip"), "example.txt", "com/example/Main.class");

    JarDirectoryStep step = new JarDirectoryStep(
        new ProjectFilesystem(zipup),
        Paths.get("output.jar"),
        ImmutableSet.of(first.getFileName(), second.getFileName()),
        "com.example.Main",
        /* manifest file */ null);
    ExecutionContext context = TestExecutionContext.newInstance();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    Path zip = zipup.resolve("output.jar");
    assertTrue(Files.exists(zip));

    // "example.txt" "Main.class" and the MANIFEST.MF.
    assertZipFileCountIs(3, zip);
    assertZipContains(zip, "example.txt");
  }

  @Test
  public void shouldFailIfMainClassMissing() throws IOException {
    Path zipup = folder.newFolder("zipup");

    Path zip = createZip(zipup.resolve("a.zip"), "com/example/Main.class");

    JarDirectoryStep step = new JarDirectoryStep(
        new ProjectFilesystem(zipup),
        Paths.get("output.jar"),
        ImmutableSet.of(zip.getFileName()),
        "com.example.MissingMain",
        /* manifest file */ null);
    TestConsole console = new TestConsole();
    ExecutionContext context = TestExecutionContext.newBuilder()
        .setConsole(console)
        .build();

    int returnCode = step.execute(context);

    assertEquals(1, returnCode);
    assertEquals(
        "ERROR: Main class com.example.MissingMain does not exist.\n",
        console.getTextWrittenToStdErr());
  }

  @Test
  public void shouldNotComplainWhenDuplicateDirectoryNamesAreAdded() throws IOException {
    Path zipup = folder.newFolder();

    Path first = createZip(zipup.resolve("first.zip"), "dir/example.txt", "dir/root1file.txt");
    Path second = createZip(
        zipup.resolve("second.zip"),
        "dir/example.txt",
        "dir/root2file.txt",
        "com/example/Main.class");

    JarDirectoryStep step = new JarDirectoryStep(
        new ProjectFilesystem(zipup),
        Paths.get("output.jar"),
        ImmutableSet.of(first.getFileName(), second.getFileName()),
        "com.example.Main",
        /* manifest file */ null);

    ExecutionContext context = TestExecutionContext.newInstance();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    Path zip = zipup.resolve("output.jar");

    // The three below plus the manifest and Main.class.
    assertZipFileCountIs(5, zip);
    assertZipContains(zip, "dir/example.txt", "dir/root1file.txt", "dir/root2file.txt");
  }

  @Test
  public void entriesFromTheGivenManifestShouldOverrideThoseInTheJars() throws IOException {
    String expected = "1.4";
    // Write the manifest, setting the implementation version
    Path tmp = folder.newFolder();

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), expected);
    Path manifestFile = tmp.resolve("manifest");
    try (OutputStream fos = Files.newOutputStream(manifestFile)) {
      manifest.write(fos);
    }

    // Write another manifest, setting the implementation version to something else
    manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), "1.0");

    Path input = tmp.resolve("input.jar");
    try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(input)) {
      ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
      out.putNextEntry(entry);
      manifest.write(out);
    }

    Path output = tmp.resolve("output.jar");
    JarDirectoryStep step = new JarDirectoryStep(
        new ProjectFilesystem(tmp),
        Paths.get("output.jar"),
        ImmutableSet.of(Paths.get("input.jar")),
        /* main class */ null,
        Paths.get("manifest"),
        /* merge manifest */ true,
        /* blacklist */ ImmutableSet.<String>of());
    ExecutionContext context = TestExecutionContext.newInstance();
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
    Path zipup = folder.newFolder("dir-zip");

    Path subdir = zipup.resolve("dir/subdir");
    Files.createDirectories(subdir);
    Files.write(subdir.resolve("a.txt"), "cake".getBytes());

    JarDirectoryStep step = new JarDirectoryStep(
        new ProjectFilesystem(zipup),
        Paths.get("output.jar"),
        ImmutableSet.of(zipup),
        /* main class */ null,
        /* manifest file */ null);
    ExecutionContext context = TestExecutionContext.newInstance();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    Path zip = zipup.resolve("output.jar");
    assertTrue(Files.exists(zip));

    // Iterate over each of the entries, expecting to see the directory names as entries.
    Set<String> expected = Sets.newHashSet("dir/", "dir/subdir/");
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(zip))) {
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
    Path zipup = folder.newFolder();

    Path first = createZip(
        zipup.resolve("first.zip"),
        "dir/file1.txt",
        "dir/file2.txt",
        "com/example/Main.class");

    JarDirectoryStep step = new JarDirectoryStep(
        new ProjectFilesystem(zipup),
        Paths.get("output.jar"),
        ImmutableSet.of(first.getFileName()),
        "com.example.Main",
        /* manifest file */ null,
        /* merge manifests */ true,
        /* blacklist */ ImmutableSet.of(".*2.*"));

    ExecutionContext context = TestExecutionContext.newInstance();

    int returnCode = step.execute(context);

    assertEquals(0, returnCode);

    Path zip = zipup.resolve("output.jar");

    // file1.txt, Main.class, plus the manifest.
    assertZipFileCountIs(3, zip);
    assertZipContains(zip, "dir/file1.txt");
    assertZipDoesNotContain(zip, "dir/file2.txt");
  }

  @Test
  public void timesAreSanitized() throws IOException {
    Path zipup = folder.newFolder("dir-zip");

    // Create a jar file with a file and a directory.
    Path subdir = zipup.resolve("dir");
    Files.createDirectories(subdir);
    Files.write(subdir.resolve("a.txt"), "cake".getBytes());
    Path outputJar = folder.getRoot().resolve("output.jar");
    JarDirectoryStep step =
        new JarDirectoryStep(
            new ProjectFilesystem(folder.getRoot()),
            outputJar,
            ImmutableSet.of(zipup),
            /* main class */ null,
            /* manifest file */ null);
    ExecutionContext context = TestExecutionContext.newInstance();
    int returnCode = step.execute(context);
    assertEquals(0, returnCode);

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    assertTrue(Files.exists(outputJar));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_EPOCH_START));
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(outputJar.toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertEquals(entry.getName(), dosEpoch, new Date(entry.getTime()));
      }
    }
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
    Path originalJar = folder.newFile("unexpected.jar");
    JarOutputStream ignored =
        new JarOutputStream(Files.newOutputStream(originalJar), fromJar);
    ignored.close();

    // Now create the actual manifest
    Path manifestFile = folder.newFile("actual_manfiest.mf");
    try (OutputStream os = Files.newOutputStream(manifestFile)) {
      fromUser.write(os);
    }

    Path tmp = folder.newFolder();
    Path output = tmp.resolve("example.jar");
    JarDirectoryStep step = new JarDirectoryStep(
        new ProjectFilesystem(tmp),
        output,
        ImmutableSortedSet.of(originalJar),
        /* main class */ null,
        manifestFile,
        mergeEntries,
        /* blacklist */ ImmutableSet.<String>of());
    ExecutionContext context = TestExecutionContext.newInstance();
    step.execute(context);

    // Now verify that the created manifest matches the expected one.
    try (JarInputStream jis = new JarInputStream(Files.newInputStream(output))) {
      return jis.getManifest();
    }
  }

  private Path createZip(Path zipFile, String... fileNames) throws IOException {
    try (Zip zip = new Zip(zipFile, true)) {
      for (String fileName : fileNames) {
        zip.add(fileName, "");
      }
    }
    return zipFile;
  }

  private void assertZipFileCountIs(int expected, Path zip) throws IOException {
    Set<String> fileNames = getFileNames(zip);

    assertEquals(fileNames.toString(), expected, fileNames.size());
  }

  private void assertZipContains(Path zip, String... files) throws IOException {
    final Set<String> contents = getFileNames(zip);

    for (String file : files) {
      assertTrue(String.format("%s -> %s", file, contents), contents.contains(file));
    }
  }

  private void assertZipDoesNotContain(Path zip, String... files) throws IOException {
    final Set<String> contents = getFileNames(zip);

    for (String file : files) {
      assertFalse(String.format("%s -> %s", file, contents), contents.contains(file));
    }
  }

  private Set<String> getFileNames(Path zipFile) throws IOException {
    try (Zip zip = new Zip(zipFile, false)) {
      return zip.getFileNames();
    }
  }

}

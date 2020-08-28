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

package com.facebook.buck.jvm.java;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION;
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.ZipArchive;
import com.facebook.buck.util.zip.CustomZipOutputStream;
import com.facebook.buck.util.zip.ZipConstants;
import com.facebook.buck.util.zip.ZipOutputStreams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.junit.Rule;
import org.junit.Test;

public class JarDirectoryStepTest {

  @Rule public TemporaryPaths folder = new TemporaryPaths();

  @Test
  public void shouldNotThrowAnExceptionWhenAddingDuplicateEntries() throws IOException {
    AbsPath zipup = folder.newFolder("zipup");

    AbsPath first = createZip(zipup.resolve("a.zip"), "example.txt");
    AbsPath second = createZip(zipup.resolve("b.zip"), "example.txt", "com/example/Main.class");

    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(RelPath.get("output.jar"))
                .setEntriesToJar(
                    toRelPathSortedSet(
                        RelPath.of(first.getFileName()), RelPath.of(second.getFileName())))
                .setMainClass(Optional.of("com.example.Main"))
                .setMergeManifests(true)
                .build());
    int returnCode = executeStep(step, zipup);

    assertEquals(0, returnCode);

    AbsPath zip = zipup.resolve("output.jar");
    assertTrue(Files.exists(zip.getPath()));

    // "example.txt" "Main.class" and the MANIFEST.MF.
    assertZipFileCountIs(3, zip);
    assertZipContains(zip, "example.txt");
  }

  @Test
  public void shouldNotifyEventBusWhenDuplicateClassesAreFound() throws IOException {
    AbsPath jarDirectory = folder.newFolder("jarDir");

    AbsPath first =
        createZip(
            jarDirectory.resolve("a.jar"),
            "com/example/Main.class",
            "com/example/common/Helper.class");
    AbsPath second = createZip(jarDirectory.resolve("b.jar"), "com/example/common/Helper.class");

    RelPath outputPath = RelPath.get("output.jar");
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(jarDirectory);
    RelPath[] relPaths = {RelPath.of(first.getFileName()), RelPath.of(second.getFileName())};
    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(outputPath)
                .setEntriesToJar(toRelPathSortedSet(relPaths))
                .setMainClass(Optional.of("com.example.Main"))
                .setMergeManifests(true)
                .build());

    StepExecutionContext context = TestExecutionContext.newInstance(jarDirectory);
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    context.getBuckEventBus().register(listener);

    executeStep(step, context);

    String expectedMessage =
        String.format(
            "Duplicate found when adding 'com/example/common/Helper.class' to '%s' from '%s'",
            filesystem.getPathForRelativePath(outputPath), second);
    assertThat(listener.getConsoleEventLogMessages(), hasItem(expectedMessage));
  }

  @Test(expected = HumanReadableException.class)
  public void shouldFailIfMainClassMissing() throws IOException {
    AbsPath zipup = folder.newFolder("zipup");

    AbsPath zip = createZip(zipup.resolve("a.zip"), "com/example/Main.class");

    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(RelPath.get("output.jar"))
                .setEntriesToJar(toRelPathSortedSet(RelPath.of(zip.getFileName())))
                .setMainClass(Optional.of("com.example.MissingMain"))
                .setMergeManifests(true)
                .build());

    try {
      executeStep(step, zipup);
    } catch (HumanReadableException e) {
      assertEquals(
          "ERROR: Main class com.example.MissingMain does not exist.",
          e.getHumanReadableErrorMessage());
      throw e;
    }
  }

  @Test
  public void shouldNotComplainWhenDuplicateDirectoryNamesAreAdded() throws IOException {
    AbsPath zipup = folder.newFolder();

    AbsPath first = createZip(zipup.resolve("first.zip"), "dir/example.txt", "dir/root1file.txt");
    AbsPath second =
        createZip(
            zipup.resolve("second.zip"),
            "dir/example.txt",
            "dir/root2file.txt",
            "com/example/Main.class");

    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(RelPath.get("output.jar"))
                .setEntriesToJar(
                    toRelPathSortedSet(
                        RelPath.of(first.getFileName()), RelPath.of(second.getFileName())))
                .setMainClass(Optional.of("com.example.Main"))
                .setMergeManifests(true)
                .build());

    int returnCode = executeStep(step, zipup);

    assertEquals(0, returnCode);

    AbsPath zip = zipup.resolve("output.jar");

    // The three below plus the manifest and Main.class.
    assertZipFileCountIs(5, zip);
    assertZipContains(zip, "dir/example.txt", "dir/root1file.txt", "dir/root2file.txt");
  }

  @Test
  public void entriesFromTheGivenManifestShouldOverrideThoseInTheJars() throws IOException {
    String expected = "1.4";
    // Write the manifest, setting the implementation version
    AbsPath tmp = folder.newFolder();

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), expected);
    AbsPath manifestFile = tmp.resolve("manifest");
    try (OutputStream fos = Files.newOutputStream(manifestFile.getPath())) {
      manifest.write(fos);
    }

    // Write another manifest, setting the implementation version to something else
    manifest = new Manifest();
    manifest.getMainAttributes().putValue(MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(IMPLEMENTATION_VERSION.toString(), "1.0");

    AbsPath input = tmp.resolve("input.jar");
    try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(input.getPath())) {
      ZipEntry entry = new ZipEntry("META-INF/MANIFEST.MF");
      out.putNextEntry(entry);
      manifest.write(out);
    }

    RelPath output = RelPath.get("output.jar");
    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(output)
                .setEntriesToJar(toRelPathSortedSet(RelPath.get("input.jar")))
                .setManifestFile(Optional.of(RelPath.get("manifest")))
                .setMergeManifests(true)
                .build());

    int returnCode = executeStep(step, tmp);
    assertEquals(0, returnCode);

    try (ZipArchive zipArchive = new ZipArchive(tmp.resolve(output).getPath(), false)) {
      byte[] rawManifest = zipArchive.readFully("META-INF/MANIFEST.MF");
      manifest = new Manifest(new ByteArrayInputStream(rawManifest));
      String version = manifest.getMainAttributes().getValue(IMPLEMENTATION_VERSION);

      assertEquals(expected, version);
    }
  }

  @Test
  public void jarsShouldContainDirectoryEntries() throws IOException {
    String dirName = "dir-zip";
    AbsPath zipup = folder.newFolder(dirName);

    AbsPath subdir = zipup.resolve("dir/subdir");
    Files.createDirectories(subdir.getPath());
    Files.write(subdir.resolve("a.txt").getPath(), "cake".getBytes());

    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(RelPath.get("output.jar"))
                .setEntriesToJar(toRelPathSortedSet(RelPath.get("")))
                .setMergeManifests(true)
                .build());
    int returnCode = executeStep(step, zipup);

    assertEquals(0, returnCode);

    AbsPath zip = zipup.resolve("output.jar");
    assertTrue(Files.exists(zip.getPath()));

    // Iterate over each of the entries, expecting to see the directory names as entries.
    Set<String> expected = Sets.newHashSet("dir/", "dir/subdir/");
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(zip.getPath()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        expected.remove(entry.getName());
      }
    }
    assertTrue("Didn't see entries for: " + expected, expected.isEmpty());
  }

  @Test
  public void shouldNotMergeManifestsIfRequested() throws IOException, InterruptedException {
    Manifest fromJar = createManifestWithExampleSection(ImmutableMap.of("Not-Seen", "ever"));
    Manifest fromUser = createManifestWithExampleSection(ImmutableMap.of("cake", "cheese"));

    Manifest seenManifest = jarDirectoryAndReadManifest(fromJar, fromUser, false);

    assertEquals(fromUser.getEntries(), seenManifest.getEntries());
  }

  @Test
  public void shouldMergeManifestsIfAsked() throws InterruptedException, IOException {
    Manifest fromJar = createManifestWithExampleSection(ImmutableMap.of("Not-Seen", "ever"));
    Manifest fromUser = createManifestWithExampleSection(ImmutableMap.of("cake", "cheese"));

    Manifest seenManifest = jarDirectoryAndReadManifest(fromJar, fromUser, true);

    Manifest expectedManifest = new Manifest(fromJar);
    Attributes attrs = new Attributes();
    attrs.putValue("Not-Seen", "ever");
    attrs.putValue("cake", "cheese");
    expectedManifest.getEntries().put("example", attrs);
    assertEquals(expectedManifest.getEntries(), seenManifest.getEntries());
  }

  @Test
  public void shouldSortManifestAttributesAndEntries() throws IOException, InterruptedException {
    Manifest fromJar =
        createManifestWithExampleSection(ImmutableMap.of("foo", "bar", "baz", "waz"));
    Manifest fromUser =
        createManifestWithExampleSection(ImmutableMap.of("bar", "foo", "waz", "baz"));

    String seenManifest =
        new String(jarDirectoryAndReadManifestContents(fromJar, fromUser, true), UTF_8);

    assertEquals(
        Joiner.on("\r\n")
            .join(
                "Manifest-Version: 1.0",
                "",
                "Name: example",
                "bar: foo",
                "baz: waz",
                "foo: bar",
                "waz: baz",
                "",
                ""),
        seenManifest);
  }

  @Test
  public void shouldNotIncludeFilesInBlacklist() throws IOException {
    AbsPath zipup = folder.newFolder();
    AbsPath first =
        createZip(
            zipup.resolve("first.zip"),
            "dir/file1.txt",
            "dir/file2.class",
            "com/example/Main.class");

    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(RelPath.get("output.jar"))
                .setEntriesToJar(toRelPathSortedSet(RelPath.of(first.getFileName())))
                .setMainClass(Optional.of("com.example.Main"))
                .setMergeManifests(true)
                .setRemoveEntryPredicate(
                    new RemoveClassesPatternsMatcher(ImmutableSet.of(Pattern.compile(".*2.*"))))
                .build());

    int returnCode = executeStep(step, zipup);
    assertEquals(0, returnCode);

    AbsPath zip = zipup.resolve("output.jar");
    // 3 files in total: file1.txt, & com/example/Main.class & the manifest.
    assertZipFileCountIs(3, zip);
    assertZipContains(zip, "dir/file1.txt");
    assertZipDoesNotContain(zip, "dir/file2.txt");
  }

  @Test
  public void shouldNotIncludeFilesInClassesToRemoveFromJar() throws IOException {
    AbsPath zipup = folder.newFolder();
    AbsPath first =
        createZip(
            zipup.resolve("first.zip"),
            "com/example/A.class",
            "com/example/B.class",
            "com/example/C.class");

    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(RelPath.get("output.jar"))
                .setEntriesToJar(toRelPathSortedSet(RelPath.of(first.getFileName())))
                .setMainClass(Optional.of("com.example.A"))
                .setMergeManifests(true)
                .setRemoveEntryPredicate(
                    new RemoveClassesPatternsMatcher(
                        ImmutableSet.of(
                            Pattern.compile("com.example.B"), Pattern.compile("com.example.C"))))
                .build());

    int returnCode = executeStep(step, zipup);
    assertEquals(0, returnCode);

    AbsPath zip = zipup.resolve("output.jar");
    // 2 files in total: com/example/A/class & the manifest.
    assertZipFileCountIs(2, zip);
    assertZipContains(zip, "com/example/A.class");
    assertZipDoesNotContain(zip, "com/example/B.class");
    assertZipDoesNotContain(zip, "com/example/C.class");
  }

  @Test
  public void timesAreSanitized() throws IOException {
    AbsPath zipup = folder.newFolder("dir-zip");

    // Create a jar file with a file and a directory.
    AbsPath subdir = zipup.resolve("dir");
    Files.createDirectories(subdir.getPath());
    Files.write(subdir.resolve("a.txt").getPath(), "cake".getBytes());
    RelPath outputJar = RelPath.get("output.jar");
    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(outputJar)
                .setEntriesToJar(toRelPathSortedSet(RelPath.get("")))
                .setMergeManifests(true)
                .build());
    int returnCode = executeStep(step, zipup);
    assertEquals(0, returnCode);

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    assertTrue(Files.exists(zipup.resolve(outputJar).getPath()));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is =
        new ZipInputStream(new FileInputStream(zipup.resolve(outputJar).toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertEquals(entry.getName(), dosEpoch, new Date(entry.getTime()));
      }
    }
  }

  /**
   * From the constructor of {@link JarInputStream}:
   *
   * <p>This implementation assumes the META-INF/MANIFEST.MF entry should be either the first or the
   * second entry (when preceded by the dir META-INF/). It skips the META-INF/ and then "consumes"
   * the MANIFEST.MF to initialize the Manifest object.
   *
   * <p>A simple implementation of {@link JarDirectoryStep} would iterate over all entries to be
   * included, adding them to the output jar, while merging manifest files, writing the merged
   * manifest as the last item in the jar. That will generate jars the {@code JarInputStream} won't
   * be able to find the manifest for.
   */
  @Test
  public void manifestShouldBeSecondEntryInJar() throws Exception {
    Path manifestPath = Paths.get(JarFile.MANIFEST_NAME);

    // Create a directory with a manifest in it and more than two files.
    AbsPath dir = folder.newFolder();
    Manifest dirManifest = new Manifest();
    Attributes attrs = new Attributes();
    attrs.putValue("From-Dir", "cheese");
    dirManifest.getEntries().put("Section", attrs);

    Files.createDirectories(dir.resolve(manifestPath).getParent().getPath());
    try (OutputStream out = Files.newOutputStream(dir.resolve(manifestPath).getPath())) {
      dirManifest.write(out);
    }
    Files.write(dir.resolve("A.txt").getPath(), "hello world".getBytes(UTF_8));
    Files.write(dir.resolve("B.txt").getPath(), "hello world".getBytes(UTF_8));
    Files.write(dir.resolve("aa.txt").getPath(), "hello world".getBytes(UTF_8));
    Files.write(dir.resolve("bb.txt").getPath(), "hello world".getBytes(UTF_8));

    // Create a jar with a manifest and more than two other files.
    AbsPath inputJar = folder.newFile("example.jar");
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(inputJar.getPath()))) {
      byte[] data = "hello world".getBytes(UTF_8);
      ZipEntry entry = new ZipEntry("C.txt");
      zos.putNextEntry(entry);
      zos.write(data, 0, data.length);
      zos.closeEntry();

      entry = new ZipEntry("cc.txt");
      zos.putNextEntry(entry);
      zos.write(data, 0, data.length);
      zos.closeEntry();

      entry = new ZipEntry("META-INF/");
      zos.putNextEntry(entry);
      zos.closeEntry();

      // Note: at end of the stream. Technically invalid.
      entry = new ZipEntry(JarFile.MANIFEST_NAME);
      zos.putNextEntry(entry);
      Manifest zipManifest = new Manifest();
      attrs = new Attributes();
      attrs.putValue("From-Zip", "peas");
      zipManifest.getEntries().put("Section", attrs);
      zipManifest.write(zos);
      zos.closeEntry();
    }

    // Merge and check that the manifest includes everything
    AbsPath output = folder.newFile("output.jar");
    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(dir.relativize(output))
                .setEntriesToJar(toRelPathSortedSet(dir.relativize(dir), dir.relativize(inputJar)))
                .setMergeManifests(true)
                .build());

    int returnCode = executeStep(step, dir);
    assertEquals(0, returnCode);

    Manifest manifest;
    try (InputStream is = Files.newInputStream(output.getPath());
        JarInputStream jis = new JarInputStream(is)) {
      manifest = jis.getManifest();
    }

    assertNotNull(manifest);
    Attributes readAttributes = manifest.getAttributes("Section");
    assertEquals(2, readAttributes.size());
    assertEquals("cheese", readAttributes.getValue("From-Dir"));
    assertEquals("peas", readAttributes.getValue("From-Zip"));
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
      Manifest fromJar, Manifest fromUser, boolean mergeEntries) throws IOException {
    byte[] contents = jarDirectoryAndReadManifestContents(fromJar, fromUser, mergeEntries);
    return new Manifest(new ByteArrayInputStream(contents));
  }

  private byte[] jarDirectoryAndReadManifestContents(
      Manifest fromJar, Manifest fromUser, boolean mergeEntries) throws IOException {
    // Create a jar with a manifest we'd expect to see merged.
    AbsPath originalJar = folder.newFile("unexpected.jar");
    JarOutputStream ignored =
        new JarOutputStream(Files.newOutputStream(originalJar.getPath()), fromJar);
    ignored.close();

    // Now create the actual manifest
    AbsPath manifestFile = folder.newFile("actual_manfiest.mf");
    try (OutputStream os = Files.newOutputStream(manifestFile.getPath())) {
      fromUser.write(os);
    }

    AbsPath tmp = folder.newFolder();
    RelPath output = RelPath.get("example.jar");
    JarDirectoryStep step =
        new JarDirectoryStep(
            JarParameters.builder()
                .setJarPath(output)
                .setEntriesToJar(toRelPathSortedSet(tmp.relativize(originalJar)))
                .setManifestFile(Optional.of(tmp.relativize(manifestFile)))
                .setMergeManifests(mergeEntries)
                .setRemoveEntryPredicate(RemoveClassesPatternsMatcher.EMPTY)
                .build());

    executeStep(step, tmp);

    try (JarFile jf = new JarFile(tmp.resolve(output).toFile())) {
      JarEntry manifestEntry = jf.getJarEntry(JarFile.MANIFEST_NAME);
      try (InputStream manifestStream = jf.getInputStream(manifestEntry)) {
        return ByteStreams.toByteArray(manifestStream);
      }
    }
  }

  private AbsPath createZip(AbsPath zipFile, String... fileNames) throws IOException {
    try (ZipArchive zip = new ZipArchive(zipFile.getPath(), true)) {
      for (String fileName : fileNames) {
        zip.add(fileName, "");
      }
    }
    return zipFile;
  }

  private void assertZipFileCountIs(int expected, AbsPath zip) throws IOException {
    Set<String> fileNames = getFileNames(zip);

    assertEquals(fileNames.toString(), expected, fileNames.size());
  }

  private void assertZipContains(AbsPath zip, String... files) throws IOException {
    Set<String> contents = getFileNames(zip);

    for (String file : files) {
      assertTrue(String.format("%s -> %s", file, contents), contents.contains(file));
    }
  }

  private void assertZipDoesNotContain(AbsPath zip, String... files) throws IOException {
    Set<String> contents = getFileNames(zip);

    for (String file : files) {
      assertFalse(String.format("%s -> %s", file, contents), contents.contains(file));
    }
  }

  private Set<String> getFileNames(AbsPath zipFile) throws IOException {
    try (ZipArchive zip = new ZipArchive(zipFile.getPath(), false)) {
      return zip.getFileNames();
    }
  }

  private ImmutableSortedSet<RelPath> toRelPathSortedSet(RelPath... relPaths) {
    return ImmutableSortedSet.orderedBy(RelPath.comparator()).add(relPaths).build();
  }

  private int executeStep(JarDirectoryStep step, AbsPath root) throws IOException {
    return executeStep(step, TestExecutionContext.newInstance(root));
  }

  private int executeStep(JarDirectoryStep step, StepExecutionContext context) throws IOException {
    return step.executeIsolatedStep(context).getExitCode();
  }
}

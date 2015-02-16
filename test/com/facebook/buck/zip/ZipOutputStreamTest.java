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

package com.facebook.buck.zip;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;
import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.OVERWRITE_EXISTING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Calendar.SEPTEMBER;
import static java.util.zip.Deflater.BEST_COMPRESSION;
import static java.util.zip.Deflater.NO_COMPRESSION;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.MorePosixFilePermissions;
import com.facebook.buck.testutil.Zip;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipOutputStreamTest {

  private File output;

  @Before
  public void createZipFileDestination() throws IOException {
    output = File.createTempFile("example", ".zip");
  }

  @Test
  public void shouldBeAbleToCreateEmptyArchive() throws IOException {
    CustomZipOutputStream ignored = ZipOutputStreams.newOutputStream(output);
    ignored.close();

    try (Zip zip = new Zip(output, /* forWriting */ false)) {
      assertTrue(zip.getFileNames().isEmpty());
    }
  }

  @Test
  public void shouldBeAbleToCreateEmptyArchiveWhenOverwriting() throws IOException {
    CustomZipOutputStream ignored = ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING);
    ignored.close();

    try (Zip zip = new Zip(output, false)) {
      assertTrue(zip.getFileNames().isEmpty());
    }
  }


  @Test(expected = ZipException.class)
  public void mustThrowAnExceptionIfNoZipEntryIsOpenWhenWritingData() throws IOException {
    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output)
    ) {
      // Note: we have not opened a zip entry.
      out.write("cheese".getBytes());
    }
  }

  @Test(expected = ZipException.class)
  public void mustThrowAnExceptionIfNoZipEntryIsOpenWhenWritingDataWhenOverwriting()
      throws IOException {
    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING)
    ) {
      // Note: we have not opened a zip entry
      out.write("cheese".getBytes());
    }
  }


  @Test
  public void shouldBeAbleToAddAZeroLengthFile() throws IOException {
    File reference = File.createTempFile("reference", ".zip");

    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output);
        ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))
    ) {
      ZipEntry entry = new ZipEntry("example.txt");
      entry.setTime(System.currentTimeMillis());
      out.putNextEntry(entry);
      ref.putNextEntry(entry);
    }

    byte[] seen = Files.readAllBytes(output.toPath());
    byte[] expected = Files.readAllBytes(reference.toPath());

    assertArrayEquals(expected, seen);
  }

  @Test
  public void shouldBeAbleToAddAZeroLengthFileWhenOverwriting() throws IOException {
    File reference = File.createTempFile("reference", ".zip");

    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING);
        ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))
    ) {
      ZipEntry entry = new ZipEntry("example.txt");
      entry.setTime(System.currentTimeMillis());
      out.putNextEntry(entry);
      ref.putNextEntry(entry);
    }

    byte[] seen = Files.readAllBytes(output.toPath());
    byte[] expected = Files.readAllBytes(reference.toPath());

    assertArrayEquals(expected, seen);
  }

  @Test
  public void shouldBeAbleToAddTwoZeroLengthFiles() throws IOException {
    File reference = File.createTempFile("reference", ".zip");

    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output);
        ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))
    ) {
      ZipEntry entry = new ZipEntry("example.txt");
      entry.setTime(System.currentTimeMillis());
      out.putNextEntry(entry);
      ref.putNextEntry(entry);

      ZipEntry entry2 = new ZipEntry("example2.txt");
      entry2.setTime(System.currentTimeMillis());
      out.putNextEntry(entry2);
      ref.putNextEntry(entry2);
    }

    byte[] seen = Files.readAllBytes(output.toPath());
    byte[] expected = Files.readAllBytes(reference.toPath());

    assertArrayEquals(expected, seen);
  }

  @Test
  public void shouldBeAbleToAddASingleNonZeroLengthFile() throws IOException {
    File reference = File.createTempFile("reference", ".zip");

    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output);
        ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))
    ) {
      byte[] bytes = "cheese".getBytes();
      ZipEntry entry = new ZipEntry("example.txt");
      entry.setTime(System.currentTimeMillis());
      out.putNextEntry(entry);
      ref.putNextEntry(entry);
      out.write(bytes);
      ref.write(bytes);
    }

    byte[] seen = Files.readAllBytes(output.toPath());
    byte[] expected = Files.readAllBytes(reference.toPath());

    assertArrayEquals(expected, seen);
  }

  @Test(expected = ZipException.class)
  public void writingTheSameFileMoreThanOnceIsNormallyAnError() throws IOException {
    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output)
    ) {
      ZipEntry entry = new ZipEntry("example.txt");
      out.putNextEntry(entry);
      out.putNextEntry(entry);
    }
  }

  @Test
  public void shouldBeAbleToSimplyStoreInputFilesWithoutCompressing() throws IOException {
    File reference = File.createTempFile("reference", ".zip");

    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output);
        ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))
    ) {
      byte[] bytes = "cheese".getBytes();
      ZipEntry entry = new ZipEntry("example.txt");
      entry.setMethod(ZipEntry.STORED);
      entry.setTime(System.currentTimeMillis());
      entry.setSize(bytes.length);
      entry.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
      out.putNextEntry(entry);
      ref.putNextEntry(entry);
      out.write(bytes);
      ref.write(bytes);
    }

    byte[] seen = Files.readAllBytes(output.toPath());
    byte[] expected = Files.readAllBytes(reference.toPath());

    assertArrayEquals(expected, seen);
  }

  @Test
  public void writingTheSameFileMoreThanOnceWhenInAppendModeWritesItTwiceToTheZip()
      throws IOException {
    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, APPEND_TO_ZIP)
    ) {
      ZipEntry entry = new ZipEntry("example.txt");
      out.putNextEntry(entry);
      out.write("cheese".getBytes());
      out.putNextEntry(entry);
      out.write("cake".getBytes());
    }

    List<String> names = Lists.newArrayList();
    try (ZipInputStream in = new ZipInputStream(new FileInputStream(output))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        names.add(entry.getName());
      }
    }

    assertEquals(ImmutableList.of("example.txt", "example.txt"), names);
  }

  @Test
  public void writingTheSameFileMoreThanOnceWhenInOverwriteModeWritesItOnceToTheZip()
      throws IOException {
    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING)
    ) {
      ZipEntry entry = new ZipEntry("example.txt");
      out.putNextEntry(entry);
      out.write("cheese".getBytes());
      out.putNextEntry(entry);
      out.write("cake".getBytes());
    }

    List<String> names = Lists.newArrayList();
    try (ZipInputStream in = new ZipInputStream(new FileInputStream(output))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        assertEquals("example.txt", entry.getName());
        names.add(entry.getName());
        String out = CharStreams.toString(new InputStreamReader(in));
        assertEquals("cake", out);
      }
    }

    assertEquals(1, names.size());
  }

  @Test
  public void shouldSetTimestampOfEntries() throws IOException {
    Calendar cal = Calendar.getInstance();
    cal.set(1999, SEPTEMBER, 10);
    long old = getTimeRoundedToSeconds(cal.getTime());

    long now = getTimeRoundedToSeconds(new Date());

    try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output)) {
      ZipEntry oldAndValid = new ZipEntry("oldAndValid");
      oldAndValid.setTime(old);
      out.putNextEntry(oldAndValid);

      ZipEntry current = new ZipEntry("current");
      current.setTime(now);
      out.putNextEntry(current);
    }

    try (ZipInputStream in = new ZipInputStream(new FileInputStream(output))) {
      ZipEntry entry = in.getNextEntry();
      assertEquals("oldAndValid", entry.getName());
      assertEquals(old, entry.getTime());

      entry = in.getNextEntry();
      assertEquals("current", entry.getName());
      assertEquals(now, entry.getTime());
    }
  }

  private long getTimeRoundedToSeconds(Date date) {
    long time = date.getTime();

    // Work in seconds.
    time = time / 1000;

    // the dos time function is only correct to 2 seconds.
    // http://msdn.microsoft.com/en-us/library/ms724247%28v=vs.85%29.aspx
    if (time % 2 == 1) {
      time += 1;
    }

    // Back to milliseconds
    time *= 1000;

    return time;
  }

  @Test
  public void compressionCanBeSetOnAPerFileBasisAndIsHonoured() throws IOException {
    // Create some input that can be compressed.
    String packageName = getClass().getPackage().getName().replace(".", "/");
    URL sample = Resources.getResource(packageName + "/sample-bytes.properties");
    byte[] input = Resources.toByteArray(sample);

    try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output)) {
      CustomZipEntry entry = new CustomZipEntry("default");
      // Don't set the compression level. Should be the default.
      out.putNextEntry(entry);
      out.write(input);

      entry = new CustomZipEntry("stored");
      entry.setCompressionLevel(NO_COMPRESSION);
      byte[] bytes = "stored".getBytes();
      entry.setSize(bytes.length);
      entry.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
      out.putNextEntry(entry);

      out.write(bytes);

      entry = new CustomZipEntry("best");
      entry.setCompressionLevel(BEST_COMPRESSION);
      out.putNextEntry(entry);
      out.write(input);
    }

    try (ZipInputStream in = new ZipInputStream(new FileInputStream(output))) {
      ZipEntry entry = in.getNextEntry();
      assertEquals("default", entry.getName());
      ByteStreams.copy(in, ByteStreams.nullOutputStream());
      long defaultCompressedSize = entry.getCompressedSize();
      assertNotEquals(entry.getCompressedSize(), entry.getSize());

      entry = in.getNextEntry();
      ByteStreams.copy(in, ByteStreams.nullOutputStream());
      assertEquals("stored", entry.getName());
      assertEquals(entry.getCompressedSize(), entry.getSize());

      entry = in.getNextEntry();
      ByteStreams.copy(in, ByteStreams.nullOutputStream());
      assertEquals("best", entry.getName());
      ByteStreams.copy(in, ByteStreams.nullOutputStream());
      assertThat(entry.getCompressedSize(), lessThan(defaultCompressedSize));
    }
  }

  @Test
  public void shouldChangeMethodWhenCompressionLevelIsChanged() {
    CustomZipEntry entry = new CustomZipEntry("cake");
    assertEquals(ZipEntry.DEFLATED, entry.getMethod());
    assertEquals(Deflater.DEFAULT_COMPRESSION, entry.getCompressionLevel());

    entry.setCompressionLevel(NO_COMPRESSION);
    assertEquals(ZipEntry.STORED, entry.getMethod());

    entry.setCompressionLevel(BEST_COMPRESSION);
    assertEquals(ZipEntry.DEFLATED, entry.getMethod());
  }

  @Test
  public void canWriteContentToStoredZips() throws IOException {
    File overwriteZip = File.createTempFile("overwrite", ".zip");

    byte[] input = "I like cheese".getBytes(UTF_8);

    try (
      CustomZipOutputStream overwrite = ZipOutputStreams.newOutputStream(
          overwriteZip, OVERWRITE_EXISTING);
      CustomZipOutputStream appending = ZipOutputStreams.newOutputStream(output, APPEND_TO_ZIP)
    ) {
      CustomZipEntry entry = new CustomZipEntry("cheese.txt");
      entry.setCompressionLevel(NO_COMPRESSION);
      entry.setTime(0);
      overwrite.putNextEntry(entry);
      appending.putNextEntry(entry);
      overwrite.write(input);
      appending.write(input);
    }
  }

  @Test
  public void packingALargeFileShouldGenerateTheSameOutputWhenOverwritingAsWhenAppending()
      throws IOException {
    File reference = File.createTempFile("reference", ".zip");
    String packageName = getClass().getPackage().getName().replace(".", "/");
    URL sample = Resources.getResource(packageName + "/macbeth.properties");
    byte[] input = Resources.toByteArray(sample);

    try (
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING);
        ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))
    ) {
      CustomZipEntry entry = new CustomZipEntry("macbeth.properties");
      entry.setTime(System.currentTimeMillis());
      out.putNextEntry(entry);
      ref.putNextEntry(entry);
      out.write(input);
      ref.write(input);
    }

    byte[] seen = Files.readAllBytes(output.toPath());
    byte[] expected = Files.readAllBytes(reference.toPath());

    // Make sure the output is valid.
    try (ZipInputStream in = new ZipInputStream(new FileInputStream(output))) {
      ZipEntry entry = in.getNextEntry();
      assertEquals("macbeth.properties", entry.getName());
      assertNull(in.getNextEntry());
    }

    assertArrayEquals(expected, seen);
  }

  @Test
  public void testThatExternalAttributesFieldIsFunctional()
      throws IOException {

    // Prepare some sample modes to write into the zip file.
    final ImmutableList<String> samples = ImmutableList.of(
        "rwxrwxrwx",
        "rw-r--r--",
        "--x--x--x",
        "---------"
    );

    for (String stringMode : samples) {
      long mode = MorePosixFilePermissions.toMode(PosixFilePermissions.fromString(stringMode));

      // Write a tiny sample zip file, which sets the external attributes per the
      // permission sample above.
      try (CustomZipOutputStream out =
               ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING)) {
        CustomZipEntry entry = new CustomZipEntry("test");
        entry.setTime(System.currentTimeMillis());
        entry.setExternalAttributes(mode << 16);
        out.putNextEntry(entry);
        out.write(new byte[0]);
      }

      // Now re-read the zip file using apache's commons-compress, which supports parsing
      // the external attributes field.
      try (ZipFile in = new ZipFile(output)) {
        Enumeration<ZipArchiveEntry> entries = in.getEntries();
        ZipArchiveEntry entry = entries.nextElement();
        assertEquals(mode, entry.getExternalAttributes() >> 16);
        assertFalse(entries.hasMoreElements());
      }
    }
  }

}

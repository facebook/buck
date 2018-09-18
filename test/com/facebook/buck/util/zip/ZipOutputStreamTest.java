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

package com.facebook.buck.util.zip;

import static com.facebook.buck.util.zip.ZipOutputStreams.HandleDuplicates;
import static com.facebook.buck.util.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;
import static com.facebook.buck.util.zip.ZipOutputStreams.HandleDuplicates.OVERWRITE_EXISTING;
import static com.facebook.buck.util.zip.ZipOutputStreams.HandleDuplicates.THROW_EXCEPTION;
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

import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.facebook.buck.testutil.ZipArchive;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class ZipOutputStreamTest {

  @RunWith(Parameterized.class)
  public static class ModeIndependentTests {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
      return Arrays.asList(
          new Object[][] {
            {THROW_EXCEPTION}, {APPEND_TO_ZIP}, {OVERWRITE_EXISTING},
          });
    }

    @Parameterized.Parameter(0)
    public HandleDuplicates mode;

    private Path output;

    @Before
    public void createZipFileDestination() throws IOException {
      output = Files.createTempFile("example", ".zip");
    }

    @Test
    public void shouldBeAbleToCreateEmptyArchive() throws IOException {
      CustomZipOutputStream ignored = ZipOutputStreams.newOutputStream(output, mode);
      ignored.close();

      try (ZipArchive zipArchive = new ZipArchive(output, /* forWriting */ false)) {
        assertTrue(zipArchive.getFileNames().isEmpty());
      }
    }

    @Test(expected = ZipException.class)
    public void writeMustThrowAnExceptionIfNoZipEntryIsOpen() throws IOException {
      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode)) {
        // Note: we have not opened a zip entry.
        out.write("cheese".getBytes());
      }
    }

    @Test
    public void shouldBeAbleToAddAZeroLengthFile() throws IOException {
      File reference = File.createTempFile("reference", ".zip");

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode);
          ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))) {
        ZipEntry entry = new ZipEntry("example.txt");
        entry.setTime(System.currentTimeMillis());
        out.putNextEntry(entry);
        ref.putNextEntry(entry);
      }

      byte[] seen = Files.readAllBytes(output);
      byte[] expected = Files.readAllBytes(reference.toPath());

      assertArrayEquals(expected, seen);
    }

    @Test
    public void shouldBeAbleToAddTwoZeroLengthFiles() throws IOException {
      File reference = File.createTempFile("reference", ".zip");

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode);
          ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))) {
        ZipEntry entry = new ZipEntry("example.txt");
        entry.setTime(System.currentTimeMillis());
        out.putNextEntry(entry);
        ref.putNextEntry(entry);

        ZipEntry entry2 = new ZipEntry("example2.txt");
        entry2.setTime(System.currentTimeMillis());
        out.putNextEntry(entry2);
        ref.putNextEntry(entry2);
      }

      byte[] seen = Files.readAllBytes(output);
      byte[] expected = Files.readAllBytes(reference.toPath());

      assertArrayEquals(expected, seen);
    }

    @Test
    public void shouldBeAbleToAddASingleNonZeroLengthFile() throws IOException {
      File reference = File.createTempFile("reference", ".zip");

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode);
          ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))) {
        byte[] bytes = "cheese".getBytes();
        ZipEntry entry = new ZipEntry("example.txt");
        entry.setTime(System.currentTimeMillis());
        out.putNextEntry(entry);
        ref.putNextEntry(entry);
        out.write(bytes);
        ref.write(bytes);
      }

      byte[] seen = Files.readAllBytes(output);
      byte[] expected = Files.readAllBytes(reference.toPath());

      assertArrayEquals(expected, seen);
    }

    @Test
    public void shouldSetTimestampOfEntries() throws IOException {
      Calendar cal = Calendar.getInstance();
      cal.set(1999, SEPTEMBER, 10);
      long old = getTimeRoundedToSeconds(cal.getTime());

      long now = getTimeRoundedToSeconds(new Date());

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode)) {
        ZipEntry oldAndValid = new ZipEntry("oldAndValid");
        oldAndValid.setTime(old);
        out.putNextEntry(oldAndValid);

        ZipEntry current = new ZipEntry("current");
        current.setTime(now);
        out.putNextEntry(current);
      }

      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(output))) {
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
      String packageName = getClass().getPackage().getName().replace('.', '/');
      URL sample = Resources.getResource(packageName + "/sample-bytes.dat");
      byte[] input = Resources.toByteArray(sample);

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode)) {
        CustomZipEntry entry = new CustomZipEntry("default");
        // Don't set the compression level. Should be the default.
        out.putNextEntry(entry);
        out.write(input);

        entry = new CustomZipEntry("stored");
        entry.setCompressionLevel(NO_COMPRESSION);
        entry.setSize(input.length);
        entry.setCompressedSize(input.length);
        entry.setCrc(Hashing.crc32().hashBytes(input).padToLong());
        out.putNextEntry(entry);
        out.write(input);

        entry = new CustomZipEntry("best");
        entry.setCompressionLevel(BEST_COMPRESSION);
        out.putNextEntry(entry);
        out.write(input);
      }

      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(output))) {
        ZipEntry entry = in.getNextEntry();
        assertEquals("default", entry.getName());
        assertArrayEquals(input, ByteStreams.toByteArray(in));
        long defaultCompressedSize = entry.getCompressedSize();
        assertNotEquals(entry.getSize(), entry.getCompressedSize());

        entry = in.getNextEntry();
        assertEquals("stored", entry.getName());
        assertArrayEquals(input, ByteStreams.toByteArray(in));
        assertEquals(entry.getSize(), entry.getCompressedSize());

        entry = in.getNextEntry();
        assertEquals("best", entry.getName());
        assertArrayEquals(input, ByteStreams.toByteArray(in));
        assertThat(entry.getCompressedSize(), lessThan(defaultCompressedSize));
      }
    }

    @Test
    public void packingALargeFileShouldGenerateTheSameOutputAsReferenceImpl() throws IOException {
      File reference = File.createTempFile("reference", ".zip");
      String packageName = getClass().getPackage().getName().replace('.', '/');
      URL sample = Resources.getResource(packageName + "/macbeth.dat");
      byte[] input = Resources.toByteArray(sample);

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode);
          ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))) {
        CustomZipEntry entry = new CustomZipEntry("macbeth.dat");
        entry.setTime(System.currentTimeMillis());
        out.putNextEntry(entry);
        ref.putNextEntry(entry);
        out.write(input);
        ref.write(input);
      }

      // Make sure the output is valid.
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(output))) {
        ZipEntry entry = in.getNextEntry();
        assertEquals("macbeth.dat", entry.getName());
        assertArrayEquals(input, ByteStreams.toByteArray(in));
        assertNull(in.getNextEntry());
      }

      byte[] seen = Files.readAllBytes(output);
      byte[] expected = Files.readAllBytes(reference.toPath());
      assertArrayEquals(expected, seen);
    }

    @Test
    public void testThatExternalAttributesFieldIsFunctional() throws IOException {

      // Prepare some sample modes to write into the zip file.
      ImmutableList<String> samplePermissions =
          ImmutableList.of("rwxrwxrwx", "rw-r--r--", "--x--x--x", "---------");

      for (String stringPermissions : samplePermissions) {
        long permissions =
            MorePosixFilePermissions.toMode(PosixFilePermissions.fromString(stringPermissions));

        // Write a tiny sample zip file, which sets the external attributes per the
        // permission sample above.
        try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, mode)) {
          CustomZipEntry entry = new CustomZipEntry("test");
          entry.setTime(System.currentTimeMillis());
          entry.setExternalAttributes(permissions << 16);
          out.putNextEntry(entry);
          out.write(new byte[0]);
        }

        // Now re-read the zip file using apache's commons-compress, which supports parsing
        // the external attributes field.
        try (ZipFile in = new ZipFile(output.toFile())) {
          Enumeration<ZipArchiveEntry> entries = in.getEntries();
          ZipArchiveEntry entry = entries.nextElement();
          assertEquals(permissions, entry.getExternalAttributes() >> 16);
          assertFalse(entries.hasMoreElements());
        }
      }
    }
  }

  public static class ModeDependentTests {
    private Path output;

    @Before
    public void createZipFileDestination() throws IOException {
      output = Files.createTempFile("example", ".zip");
    }

    @Test(expected = ZipException.class)
    public void writingTheSameFileMoreThanOnceIsNormallyAnError() throws IOException {
      // Default HandleDuplicate mode is THROW_EXCEPTION
      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output)) {
        ZipEntry entry = new ZipEntry("example.txt");
        out.putNextEntry(entry);
        out.putNextEntry(entry);
      }
    }

    @Test
    public void writingTheSameFileMoreThanOnceWhenInAppendModeWritesItTwiceToTheZip()
        throws IOException {
      String name = "example.txt";
      byte[] input1 = "cheese".getBytes(UTF_8);
      byte[] input2 = "cake".getBytes(UTF_8);
      byte[] input3 = "dessert".getBytes(UTF_8);
      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, APPEND_TO_ZIP)) {
        ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(input1);
        out.putNextEntry(entry);
        out.write(input2);
        out.putNextEntry(entry);
        out.write(input3);
      }

      assertEquals(
          ImmutableList.of(
              new NameAndContent(name, input1),
              new NameAndContent(name, input2),
              new NameAndContent(name, input3)),
          getExtractedEntries(output));
    }

    @Test
    public void writingTheSameFileMoreThanOnceWhenInOverwriteModeWritesItOnceToTheZip()
        throws IOException {
      final String name = "example.txt";
      byte[] input1 = "cheese".getBytes(UTF_8);
      byte[] input2 = "cake".getBytes(UTF_8);
      byte[] input3 = "dessert".getBytes(UTF_8);
      try (CustomZipOutputStream out =
          ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING)) {
        ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(input1);
        out.putNextEntry(entry);
        out.write(input2);
        out.putNextEntry(entry);
        out.write(input3);
      }

      assertEquals(ImmutableList.of(new NameAndContent(name, input3)), getExtractedEntries(output));
    }

    @Test
    public void canWriteContentToStoredZipsInModeThrow() throws IOException {
      String name = "cheese.txt";
      byte[] input = "I like cheese".getBytes(UTF_8);
      File reference = File.createTempFile("reference", ".zip");

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, THROW_EXCEPTION);
          ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))) {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setTime(System.currentTimeMillis());
        entry.setSize(input.length);
        entry.setCompressedSize(input.length);
        entry.setCrc(calcCrc(input));
        out.putNextEntry(entry);
        ref.putNextEntry(entry);
        out.write(input);
        ref.write(input);
      }

      assertEquals(ImmutableList.of(new NameAndContent(name, input)), getExtractedEntries(output));

      // also check against the reference implementation
      byte[] seen = Files.readAllBytes(output);
      byte[] expected = Files.readAllBytes(reference.toPath());
      assertArrayEquals(expected, seen);
    }

    @Test
    public void canWriteContentToStoredZipsInModeAppend() throws IOException {
      String name = "cheese.txt";
      byte[] input1 = "I like cheese 1".getBytes(UTF_8);
      byte[] input2 = "I like cheese 2".getBytes(UTF_8);

      try (CustomZipOutputStream out = ZipOutputStreams.newOutputStream(output, APPEND_TO_ZIP)) {
        CustomZipEntry entry1 = new CustomZipEntry(name);
        entry1.setCompressionLevel(NO_COMPRESSION);
        entry1.setTime(0);
        entry1.setSize(input1.length);
        entry1.setCompressedSize(input1.length);
        entry1.setCrc(calcCrc(input1));
        out.putNextEntry(entry1);
        out.write(input1);
        CustomZipEntry entry2 = new CustomZipEntry(name);
        entry2.setCompressionLevel(NO_COMPRESSION);
        entry2.setTime(0);
        entry2.setSize(input2.length);
        entry2.setCompressedSize(input2.length);
        entry2.setCrc(calcCrc(input2));
        out.putNextEntry(entry2);
        out.write(input2);
      }

      assertEquals(
          ImmutableList.of(new NameAndContent(name, input1), new NameAndContent(name, input2)),
          getExtractedEntries(output));
    }

    @Test
    public void canWriteContentToStoredZipsInModeOverwrite() throws IOException {
      String name = "cheese.txt";
      byte[] input1 = "I like cheese 1".getBytes(UTF_8);
      byte[] input2 = "I like cheese 2".getBytes(UTF_8);
      File reference = File.createTempFile("reference", ".zip");

      try (CustomZipOutputStream out =
              ZipOutputStreams.newOutputStream(output, OVERWRITE_EXISTING);
          ZipOutputStream ref = new ZipOutputStream(new FileOutputStream(reference))) {
        CustomZipEntry entry1 = new CustomZipEntry(name);
        entry1.setCompressionLevel(NO_COMPRESSION);
        entry1.setTime(System.currentTimeMillis());
        entry1.setSize(input1.length);
        entry1.setCompressedSize(input1.length);
        entry1.setCrc(calcCrc(input1));
        out.putNextEntry(entry1);
        out.write(input1);

        CustomZipEntry entry2 = new CustomZipEntry(name);
        entry2.setCompressionLevel(NO_COMPRESSION);
        entry2.setTime(System.currentTimeMillis());
        entry2.setSize(input2.length);
        entry2.setCompressedSize(input2.length);
        entry2.setCrc(calcCrc(input2));
        out.putNextEntry(entry2);
        ref.putNextEntry(entry2);
        out.write(input2);
        ref.write(input2);
      }

      assertEquals(ImmutableList.of(new NameAndContent(name, input2)), getExtractedEntries(output));

      // also check against the reference implementation
      byte[] seen = Files.readAllBytes(output);
      byte[] expected = Files.readAllBytes(reference.toPath());
      assertArrayEquals(expected, seen);
    }
  }

  private static List<NameAndContent> getExtractedEntries(Path zipFile) throws IOException {
    List<NameAndContent> entries = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zipFile))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.add(new NameAndContent(entry.getName(), ByteStreams.toByteArray(in)));
      }
    }
    return entries;
  }

  private static long calcCrc(byte[] bytes) {
    return Hashing.crc32().hashBytes(bytes).padToLong();
  }

  private static class NameAndContent {
    public final String name;
    public final byte[] content;

    public NameAndContent(String name, byte[] content) {
      this.name = name;
      this.content = content;
    }

    @Override
    public int hashCode() {
      return 0;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof NameAndContent)) {
        return false;
      }
      NameAndContent that = (NameAndContent) obj;
      return name.equals(that.name) && Arrays.equals(content, that.content);
    }
  }
}

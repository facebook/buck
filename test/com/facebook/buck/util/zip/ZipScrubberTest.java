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

package com.facebook.buck.util.zip;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNull;

import com.google.common.io.Resources;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ZipScrubberTest {

  @Test
  public void modificationTimes() throws Exception {
    // Create a dummy ZIP file.
    ByteArrayOutputStream bytesOutputStream = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytesOutputStream)) {
      ZipEntry entry = new ZipEntry("file1");
      byte[] data = "data1".getBytes(StandardCharsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();

      entry = new ZipEntry("file2");
      data = "data2".getBytes(StandardCharsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();
    }

    byte[] bytes = bytesOutputStream.toByteArray();
    // Execute the zip scrubber step.
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void modificationTimesExceedShort() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    byte[] data = "data1".getBytes(StandardCharsets.UTF_8);
    try (ZipOutputStream out = new ZipOutputStream(byteArrayOutputStream)) {
      for (long i = 0; i < Short.MAX_VALUE + 1; i++) {
        ZipEntry entry = new ZipEntry("file" + i);
        entry.setSize(data.length);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
      }
    }

    byte[] bytes = byteArrayOutputStream.toByteArray();
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void modificationZip64Times() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    byte[] data = "data1".getBytes(StandardCharsets.UTF_8);
    try (ZipOutputStream out = new ZipOutputStream(byteArrayOutputStream)) {
      for (long i = 0; i < 2 * Short.MAX_VALUE + 1; i++) {
        ZipEntry entry = new ZipEntry("file" + i);
        entry.setSize(data.length);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
      }
    }

    byte[] bytes = byteArrayOutputStream.toByteArray();
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void modificationTimesSmallPadding() throws Exception {
    // Small test file that has padding (inserted by `zipalign 4`).
    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL sample = Resources.getResource(packageName + "/aligned.4.zip");
    byte[] bytes = Resources.toByteArray(sample);

    // Execute the zip scrubber step.
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void modificationTimesSmallPaddingNoExtra() throws Exception {
    // Small test file that has padding (inserted by `zipalign 4`).
    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL sample = Resources.getResource(packageName + "/aligned.4.no_extra.zip");
    byte[] bytes = Resources.toByteArray(sample);

    // Execute the zip scrubber step.
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void modificationTimesLargePadding() throws Exception {
    // Small test file that has padding (inserted by `zipalign 4`).
    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL sample = Resources.getResource(packageName + "/aligned.page.zip");
    byte[] bytes = Resources.toByteArray(sample);

    // Execute the zip scrubber step.
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void forceSlidingWindowMovement() throws Exception {
    // This test matches modificationTimesLargePadding, but forces a small sliding window.

    // Small test file that has padding (inserted by `zipalign 4`).
    String packageName = getClass().getPackage().getName().replace('.', '/');
    URL sample = Resources.getResource(packageName + "/aligned.page.zip");
    byte[] bytes = Resources.toByteArray(sample);

    // Execute the zip scrubber step with the smallest window possible.
    ZipScrubber.scrubZipBuffer(SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), 8));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void zip64Fields() throws Exception {
    byte[] data = "data1".getBytes(StandardCharsets.UTF_8);

    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(byteArrayOutputStream)) {
      out.setUseZip64(Zip64Mode.Always);
      for (long i = 0; i < 2 * Short.MAX_VALUE + 1; i++) {
        ZipArchiveEntry zipEntry = new ZipArchiveEntry("file" + i);
        zipEntry.setSize(data.length);
        zipEntry.setCompressedSize(zipEntry.getSize());
        out.putArchiveEntry(zipEntry);
        out.write(data);
        out.closeArchiveEntry();
      }
    }

    byte[] bytes = byteArrayOutputStream.toByteArray();
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipArchiveInputStream is = new ZipArchiveInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipArchiveEntry entry = is.getNextZipEntry();
          entry != null;
          entry = is.getNextZipEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void emptyZip32File() throws Exception {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(byteArrayOutputStream)) {
      // Don't add anything to the zip file.
    }

    byte[] bytes = byteArrayOutputStream.toByteArray();
    ZipScrubber.scrubZipBuffer(
        SlidingFileWindow.withByteBuffer(ByteBuffer.wrap(bytes), Integer.MAX_VALUE));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      assertNull(is.getNextEntry());
    }
  }
}

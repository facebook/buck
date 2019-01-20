/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;

import com.google.common.base.Charsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
      byte[] data = "data1".getBytes(Charsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();

      entry = new ZipEntry("file2");
      data = "data2".getBytes(Charsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();
    }

    byte[] bytes = bytesOutputStream.toByteArray();
    // Execute the zip scrubber step.
    ZipScrubber.scrubZipBuffer(bytes.length, ByteBuffer.wrap(bytes));

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
    byte[] data = "data1".getBytes(Charsets.UTF_8);
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
    ZipScrubber.scrubZipBuffer(bytes.length, ByteBuffer.wrap(bytes));

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
    byte[] data = "data1".getBytes(Charsets.UTF_8);
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
    ZipScrubber.scrubZipBuffer(bytes.length, ByteBuffer.wrap(bytes));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }
}

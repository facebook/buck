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

package com.facebook.buck.util.zip;

import static java.util.zip.Deflater.BEST_COMPRESSION;
import static java.util.zip.Deflater.DEFAULT_COMPRESSION;
import static java.util.zip.Deflater.NO_COMPRESSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TimeZone;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CustomZipEntryTest {

  @Test
  public void shouldChangeMethodWhenCompressionLevelIsChanged() {
    CustomZipEntry entry = new CustomZipEntry("cake");
    assertEquals(ZipEntry.DEFLATED, entry.getMethod());
    assertEquals(DEFAULT_COMPRESSION, entry.getCompressionLevel());

    entry.setCompressionLevel(NO_COMPRESSION);
    assertEquals(ZipEntry.STORED, entry.getMethod());
    assertEquals(NO_COMPRESSION, entry.getCompressionLevel());

    entry.setCompressionLevel(BEST_COMPRESSION);
    assertEquals(ZipEntry.DEFLATED, entry.getMethod());
    assertEquals(BEST_COMPRESSION, entry.getCompressionLevel());
  }

  @Test
  public void producedZipFilesAreTimezoneAgnostic() throws Exception {
    HashCode referenceHash = writeSimpleJarAndGetHash();
    TimeZone previousDefault = TimeZone.getDefault();
    try {
      String[] availableIDs = TimeZone.getAvailableIDs();
      assertThat(availableIDs.length, Matchers.greaterThan(1));

      for (String timezoneID : availableIDs) {
        TimeZone timeZone = TimeZone.getTimeZone(timezoneID);
        TimeZone.setDefault(timeZone);

        assertThat(writeSimpleJarAndGetHash(), Matchers.equalTo(referenceHash));
      }
    } finally {
      TimeZone.setDefault(previousDefault);
    }
  }

  private HashCode writeSimpleJarAndGetHash() throws Exception {
    Path output = Files.createTempFile("example", ".jar");
    try (FileOutputStream fileOutputStream = new FileOutputStream(output.toFile());
        ZipOutputStream out = new JarOutputStream(fileOutputStream)) {
      ZipEntry entry = new CustomZipEntry("test");
      out.putNextEntry(entry);
      out.write(new byte[0]);
      entry = new ZipEntry("test1");
      entry.setTime(ZipConstants.getFakeTime());
      out.putNextEntry(entry);
      out.write(new byte[0]);
    }

    return Hashing.sha1().hashBytes(Files.readAllBytes(output));
  }
}

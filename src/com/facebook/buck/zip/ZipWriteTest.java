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

package com.facebook.buck.zip;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A small test program to exercize CustomZipOutputStream.
 *
 * I wrote this to investigate a bug.  Feel free to use or modify it for anything.
 */
public class ZipWriteTest {
  private ZipWriteTest() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    try (CustomZipOutputStream zipOut = ZipOutputStreams.newOutputStream(
        Paths.get("/dev/null"), ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP)) {
      try (ZipFile zipIn = new ZipFile(new File(args[0]))) {
        for (
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            entries.hasMoreElements();
            ) {
          ZipEntry entry = entries.nextElement();
          ZipEntry newEntry = new ZipEntry(entry);
          if (entry.getMethod() == ZipEntry.DEFLATED) {
            newEntry.setCompressedSize(-1);
          }
          zipOut.putNextEntry(newEntry);
          InputStream inputStream = zipIn.getInputStream(entry);
          ByteStreams.copy(inputStream, zipOut);
          zipOut.closeEntry();
        }
      }
    }

    System.gc();
    Thread.sleep(TimeUnit.SECONDS.toMillis(1));
    System.gc();
    Thread.sleep(TimeUnit.SECONDS.toMillis(5));
  }
}

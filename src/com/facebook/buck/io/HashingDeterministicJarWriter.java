/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.io;

import com.facebook.buck.zip.ZipConstants;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;

import java.io.IOException;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a deterministic jar with precomputed hash codes in its MANIFEST.MF.
 */
public class HashingDeterministicJarWriter implements AutoCloseable {
  public static final String DIGEST_ATTRIBUTE_NAME = "Murmur3-128-Digest";

  private final ZipOutputStream jar;
  private final DeterministicJarManifestWriter manifestWriter;

  public HashingDeterministicJarWriter(ZipOutputStream jar) {
    this.jar = jar;
    manifestWriter = new DeterministicJarManifestWriter(jar);
  }

  public HashingDeterministicJarWriter writeEntry(
      String name,
      ByteSource contents) throws IOException {
    writeToJar(name, contents);
    manifestWriter.setEntryAttribute(
        name,
        DIGEST_ATTRIBUTE_NAME,
        contents.hash(Hashing.murmur3_128()).toString());
    return this;
  }

  public HashingDeterministicJarWriter writeUnhashedEntry(
      String name,
      ByteSource contents) throws IOException {
    writeToJar(name, contents);
    return this;
  }

  @Override
  public void close() throws IOException {
    if (manifestWriter.hasEntries()) {
      putEntry(jar, JarFile.MANIFEST_NAME);
      manifestWriter.write();
      jar.closeEntry();
    }
    jar.close();
  }

  private void writeToJar(String fileName, ByteSource stubClassBytes) throws IOException {
    putEntry(jar, fileName);
    stubClassBytes.copyTo(jar);
    jar.closeEntry();
  }

  private void putEntry(ZipOutputStream jar, String fileName) throws IOException {
    ZipEntry entry = new ZipEntry(fileName);
    // We want deterministic JARs, so avoid mtimes.
    entry.setTime(ZipConstants.getFakeTime());
    jar.putNextEntry(entry);
  }
}

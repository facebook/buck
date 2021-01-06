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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import org.junit.Before;
import org.junit.Test;

public class CustomJarOutputStreamTest {
  private ByteArrayOutputStream out;
  private CustomJarOutputStream writer;

  @Before
  public void setUp() {
    out = new ByteArrayOutputStream();
    writer = ZipOutputStreams.newJarOutputStream(out);
    writer.setEntryHashingEnabled(true);
  }

  @Test
  public void entriesAreWrittenAsTheyAreEncounteredWithManifestLast() throws IOException {
    writer.writeEntry(
        "Z", new ByteArrayInputStream("Z's contents".getBytes(StandardCharsets.UTF_8)));
    writer.writeEntry(
        "A", new ByteArrayInputStream("A's contents".getBytes(StandardCharsets.UTF_8)));
    writer.close();

    try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      JarEntry entry;
      entry = jar.getNextJarEntry();
      assertEquals("Z", entry.getName());

      entry = jar.getNextJarEntry();
      assertEquals("A", entry.getName());

      entry = jar.getNextJarEntry();
      assertEquals(JarFile.MANIFEST_NAME, entry.getName());
    }
  }

  @Test
  public void manifestContainsEntryHashesOfHashedEntries() throws IOException {
    String entryName = "A";
    InputStream contents = new ByteArrayInputStream("contents".getBytes(StandardCharsets.UTF_8));
    try (HashingInputStream hashingContents =
        new HashingInputStream(Hashing.murmur3_128(), contents)) {
      writer.writeEntry(entryName, hashingContents);
      writer.close();

      String expectedHash = hashingContents.hash().toString();
      assertEntryHash(entryName, expectedHash);
    }
  }

  @Test
  public void manifestContainsEntryHashesOfEmptyHashedEntries() throws IOException {
    String entryName = "A";
    InputStream contents = new ByteArrayInputStream(new byte[0]);
    try (HashingInputStream hashingContents =
        new HashingInputStream(Hashing.murmur3_128(), contents)) {
      writer.putNextEntry(new CustomZipEntry(entryName));
      writer.closeEntry();
      writer.close();

      String expectedHash = hashingContents.hash().toString();
      assertEntryHash(entryName, expectedHash);
    }
  }

  @Test
  public void manifestDoesNotContainEntryHashesOfDirectories() throws IOException {
    String entryName = "A/";
    writer.putNextEntry(new CustomZipEntry(entryName));
    writer.closeEntry();
    writer.close();

    assertNoEntryHash(entryName);
  }

  private void assertEntryHash(String entryName, String expectedHash) throws IOException {
    Manifest manifest = getManifest();
    assertEquals(expectedHash, manifest.getEntries().get(entryName).getValue("Murmur3-128-Digest"));
  }

  private void assertNoEntryHash(String entryName) throws IOException {
    assertFalse(getManifest().getEntries().containsKey(entryName));
  }

  private Manifest getManifest() throws IOException {
    Manifest manifest = new Manifest();
    try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      jar.getNextJarEntry();
      JarEntry manifestEntry = jar.getNextJarEntry();
      assertEquals(JarFile.MANIFEST_NAME, manifestEntry.getName());
      manifest.read(jar);
    }
    return manifest;
  }
}

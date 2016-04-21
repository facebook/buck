/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.abi;

import static org.objectweb.asm.ClassReader.SKIP_CODE;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.zip.ZipConstants;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public class StubJar {

  private final Path toMirror;

  public StubJar(Path toMirror) {
    this.toMirror = Preconditions.checkNotNull(toMirror);
  }

  public void writeTo(ProjectFilesystem filesystem, Path path) throws IOException {
    Preconditions.checkState(!filesystem.exists(path), "Output file already exists: %s)", path);

    if (path.getParent() != null && !filesystem.exists(path.getParent())) {
      filesystem.createParentDirs(path);
    }

    Walker walker = Walkers.getWalkerFor(toMirror);
    try (
        OutputStream fos = filesystem.newFileOutputStream(path);
        JarOutputStream jar = new JarOutputStream(fos)) {
      final CreateStubAction createStubAction = new CreateStubAction(jar);
      walker.walk(createStubAction);
      createStubAction.finish();
    }
  }

  private static class CreateStubAction implements FileAction {
    private final JarOutputStream jar;
    private final ImmutableSortedMap.Builder<String, HashCode> entriesMapBuilder =
        ImmutableSortedMap.naturalOrder();

    public CreateStubAction(JarOutputStream jar) {
      this.jar = jar;
    }

    @Override
    public void visit(Path relativizedPath, InputStream stream) throws IOException {
      String fileName = relativizedPath.toString();
      if (!fileName.endsWith(".class")) {
        return;
      }

      ByteSource stubClassBytes = getStubClassBytes(stream, fileName);
      writeToJar(fileName, stubClassBytes);
      recordHash(fileName, stubClassBytes);
    }

    public void finish() throws IOException {
      final ImmutableSortedMap<String, HashCode> entriesMap = entriesMapBuilder.build();
      if (entriesMap.isEmpty()) {
        return;
      }

      putEntry(jar, JarFile.MANIFEST_NAME);
      writeManifest(entriesMap, jar);
      jar.closeEntry();
    }

    private void writeManifest(
        ImmutableSortedMap<String, HashCode> entriesMap,
        OutputStream jar) throws IOException {
      JarManifestWriter writer =
          new JarManifestWriter(new OutputStreamWriter(jar, StandardCharsets.UTF_8));
      writer.writeLine();
      for (Map.Entry<String, HashCode> fileHashCode : entriesMap.entrySet()) {
        String file = fileHashCode.getKey();
        String hashCode = fileHashCode.getValue().toString();

        writer.writeEntry("Name", file);
        writer.writeEntry("Murmur3-128-Digest", hashCode);
        writer.writeLine();
      }

      writer.flush();
    }

    private ByteSource getStubClassBytes(InputStream stream,
        String fileName) throws IOException {
      ClassReader classReader = new ClassReader(stream);
      ClassMirror visitor = new ClassMirror(fileName);
      classReader.accept(visitor, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES);
      return visitor.getStubClassBytes();
    }

    private void writeToJar(String fileName, ByteSource stubClassBytes) throws IOException {
      putEntry(jar, fileName);
      stubClassBytes.copyTo(jar);
      jar.closeEntry();
    }

    private void putEntry(JarOutputStream jar, String fileName) throws IOException {
      JarEntry entry = new JarEntry(fileName);
      // We want deterministic JARs, so avoid mtimes.
      entry.setTime(ZipConstants.getFakeTime());
      jar.putNextEntry(entry);
    }

    private void recordHash(
        String fileName,
        ByteSource stubClassBytes) throws IOException {
      // We don't need a cryptographic hash, just a good one with super-low collision probability
      HashCode hashCode = stubClassBytes.hash(Hashing.murmur3_128());

      entriesMapBuilder.put(fileName, hashCode);
    }
  }
}

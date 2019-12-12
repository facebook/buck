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

package com.facebook.buck.util.zip.collect;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.util.zip.CustomZipEntry;
import com.facebook.buck.util.zip.CustomZipOutputStream;
import com.facebook.buck.util.zip.ZipOutputStreams;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Writes a {@link ZipEntrySourceCollection} to a zip file. */
public class ZipEntrySourceCollectionWriter {

  private final ProjectFilesystem projectFilesystem;

  public ZipEntrySourceCollectionWriter(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  /** Creates a zip archive in a given file by copying all entries listed in the collection. */
  public void copyToZip(ZipEntrySourceCollection collection, Path outputFile) throws IOException {
    // For every source archive contains list of entries and their positions to include when
    // copying.
    Map<Path, Multimap<String, Integer>> sourceArchiveEntries = new HashMap<>();
    collection.getSources().stream()
        .filter(ZipEntrySourceFromZip.class::isInstance)
        .map(ZipEntrySourceFromZip.class::cast)
        .forEach(
            entry ->
                sourceArchiveEntries
                    .compute(
                        entry.getSourceFilePath(),
                        (k, v) -> (v == null ? HashMultimap.create() : v))
                    .put(entry.getEntryName(), entry.getEntryPosition()));

    Set<Path> seenFiles = new HashSet<>();
    try (OutputStream baseOut = projectFilesystem.newFileOutputStream(outputFile);
        CustomZipOutputStream zip = ZipOutputStreams.newSimpleOutputStream(baseOut)) {
      for (ZipEntrySource entrySource : collection.getSources()) {
        if (!seenFiles.add(entrySource.getSourceFilePath())) {
          continue;
        }
        Path sourceFilePath = entrySource.getSourceFilePath();
        String entryName = entrySource.getEntryName();
        if (entrySource instanceof FileZipEntrySource) {
          addDirectoryEntries(zip, seenFiles, entryName);
          copyFile(zip, entryName, sourceFilePath);
        } else if (entrySource instanceof ZipEntrySourceFromZip) {
          copyZip(
              zip,
              sourceFilePath,
              seenFiles,
              sourceArchiveEntries.getOrDefault(sourceFilePath, HashMultimap.create()));
        }
      }
    }
  }

  /**
   * For a given entry name adds entries for all parent directories unless they are already added.
   */
  private void addDirectoryEntries(CustomZipOutputStream out, Set<Path> seenFiles, String entryName)
      throws IOException {
    Path entryPath = projectFilesystem.getPath(entryName).getParent();
    if (entryPath == null) {
      return;
    }
    Deque<Path> directoriesToAdd = new ArrayDeque<>(entryPath.getNameCount());
    while (entryPath != null && seenFiles.add(entryPath)) {
      directoriesToAdd.push(entryPath);
      entryPath = entryPath.getParent();
    }
    while (!directoriesToAdd.isEmpty()) {
      Path currentPath = directoriesToAdd.pop();
      CustomZipEntry entry =
          new CustomZipEntry(PathFormatter.pathWithUnixSeparators(currentPath) + "/");
      entry.setFakeTime();
      out.putNextEntry(entry);
      out.closeEntry();
    }
  }

  private void copyFile(CustomZipOutputStream out, String entryName, Path from) throws IOException {
    CustomZipEntry entry = new CustomZipEntry(entryName);
    entry.setFakeTime();
    entry.setExternalAttributes(projectFilesystem.getFileAttributesForZipEntry(from));

    out.putNextEntry(entry);
    try (InputStream input = projectFilesystem.newFileInputStream(from)) {
      ByteStreams.copy(input, out);
    }
    out.closeEntry();
  }

  private static void copyZip(
      CustomZipOutputStream out,
      Path from,
      Set<Path> seenFiles,
      Multimap<String, Integer> allowedEntries)
      throws IOException {
    try (ZipInputStream in =
        new ZipInputStream(new BufferedInputStream(Files.newInputStream(from)))) {
      int position = 0;
      for (ZipEntry entry = in.getNextEntry();
          entry != null;
          entry = in.getNextEntry(), position++) {
        if (!allowedEntries.containsKey(entry.getName())) {
          continue;
        }
        if (!allowedEntries.get(entry.getName()).contains(position)) {
          continue;
        }
        if (entry.isDirectory()) {
          seenFiles.add(Paths.get(entry.getName()));
        }
        CustomZipEntry customEntry = new CustomZipEntry(entry);
        customEntry.setFakeTime();
        out.putNextEntry(customEntry);
        ByteStreams.copy(in, out);
        out.closeEntry();
      }
    }
  }
}

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

package com.facebook.buck.java;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.DirectoryTraversal;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

public class JarDirectoryStepHelper {

  private JarDirectoryStepHelper() {}

  public static int createJarFile(
      Path pathToOutputFile,
      ImmutableSet<Path> entriesToJar,
      @Nullable String mainClass,
      @Nullable Path manifestFile,
      boolean mergeManifests,
      Iterable<Pattern> blacklist,
      ExecutionContext context) throws IOException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();

    // Write the manifest, as appropriate.
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

    Path absoluteOutputPath = filesystem.getPathForRelativePath(pathToOutputFile);
    try (CustomZipOutputStream outputFile = ZipOutputStreams.newOutputStream(
        absoluteOutputPath, APPEND_TO_ZIP)) {

      Set<String> alreadyAddedEntries = Sets.newHashSet();
      ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
      for (Path entry : entriesToJar) {
        Path file = projectFilesystem.getPathForRelativePath(entry);
        if (Files.isRegularFile(file)) {
          Preconditions.checkArgument(
              !file.equals(absoluteOutputPath),
              "Trying to put file %s into itself",
              file);
          // Assume the file is a ZIP/JAR file.
          copyZipEntriesToJar(file,
              outputFile,
              manifest,
              alreadyAddedEntries,
              context.getBuckEventBus(),
              blacklist);
        } else if (Files.isDirectory(file)) {
          addFilesInDirectoryToJar(
              file,
              outputFile,
              alreadyAddedEntries,
              context.getBuckEventBus());
        } else {
          throw new IllegalStateException("Must be a file or directory: " + file);
        }
      }

      // Read the user supplied manifest file, allowing it to overwrite existing entries in the
      // uber manifest we've built.
      if (manifestFile != null) {
        try (InputStream manifestStream = Files.newInputStream(
            filesystem.getPathForRelativePath(manifestFile))) {
          Manifest userSupplied = new Manifest(manifestStream);

          // In the common case, we want to use the merged manifests. In the uncommon case, we just
          // want to use the one the user gave us.
          if (mergeManifests) {
            merge(manifest, userSupplied);
          } else {
            manifest = userSupplied;
          }
        }
      }

      // The process of merging the manifests means that existing entries are
      // overwritten. To ensure that our main_class is set as expected, we
      // write it here.
      if (mainClass != null) {
        if (!mainClassPresent(mainClass, alreadyAddedEntries)) {
          context.getStdErr().print(
              String.format(
                  "ERROR: Main class %s does not exist.\n",
                  mainClass));
          return 1;
        }

        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
      }

      JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
      manifestEntry.setTime(0);  // We want deterministic JARs, so avoid mtimes.
      outputFile.putNextEntry(manifestEntry);
      manifest.write(outputFile);
    }

    return 0;
  }

  private static boolean mainClassPresent(
      String mainClass,
      Set<String> alreadyAddedEntries) {
    String mainClassPath = classNameToPath(mainClass);

    return alreadyAddedEntries.contains(mainClassPath);
  }

  private static String classNameToPath(String className) {
    return className.replace('.', '/') + ".class";
  }

  /**
   * @param file is assumed to be a zip file.
   * @param jar is the file being written.
   * @param manifest that should get a copy of (@code jar}'s manifest entries.
   * @param alreadyAddedEntries is used to avoid duplicate entries.
   */
  private static void copyZipEntriesToJar(
      Path file,
      final CustomZipOutputStream jar,
      Manifest manifest,
      Set<String> alreadyAddedEntries,
      BuckEventBus eventBus,
      Iterable<Pattern> blacklist) throws IOException {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      zipEntryLoop:
      for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
        ZipEntry entry = entries.nextElement();
        String entryName = entry.getName();

        if (entryName.equals(JarFile.MANIFEST_NAME)) {
          Manifest readManifest = readManifest(zip, entry);
          merge(manifest, readManifest);
          continue;
        }

        // We're in the process of merging a bunch of different jar files. These typically contain
        // just ".class" files and the manifest, but they can also include things like license files
        // from third party libraries and config files. We should include those license files within
        // the jar we're creating. Extracting them is left as an exercise for the consumer of the
        // jar.  Because we don't know which files are important, the only ones we skip are
        // duplicate class files.
        if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
          // Duplicate entries. Skip.
          eventBus.post(ConsoleEvent.create(
              determineSeverity(entry), "Duplicate found when adding file to jar: %s", entryName));
          continue;
        }

        for (Pattern p : blacklist) {
          if (p.matcher(entryName).matches()) {
            eventBus.post(ConsoleEvent.create(
                    Level.FINE, "Skipping adding file to jar: %s", entryName));
            continue zipEntryLoop;
          }
        }

        ZipEntry newEntry = new ZipEntry(entry);

        // For deflated entries, the act of re-"putting" this entry means we're re-compressing
        // the data that we've just uncompressed.  Due to various environmental issues (e.g. a
        // newer version of zlib, changed compression settings), we may end up with a different
        // compressed size.  This causes an issue in java's `java.util.zip.ZipOutputStream`
        // implementation, as it only updates the compressed size field if one of `crc`,
        // `compressedSize`, or `size` is -1.  When we copy the entry as-is, none of these are
        // -1, and we may end up with an incorrect compressed size, in which case, we'll get an
        // exception.  So, for deflated entries, reset the compressed size to -1 (as the
        // ZipEntry(String) would).
        // See https://github.com/spearce/buck/commit/8338c1c3d4a546f577eed0c9941d9f1c2ba0a1b7.
        if (entry.getMethod() == ZipEntry.DEFLATED) {
          newEntry.setCompressedSize(-1);
        }

        jar.putNextEntry(newEntry);
        InputStream inputStream = zip.getInputStream(entry);
        ByteStreams.copy(inputStream, jar);
        jar.closeEntry();
      }
    } catch (ZipException e) {
      throw new IOException("Failed to process zip file " + file + ": " + e.getMessage(), e);
    }
  }

  private static Level determineSeverity(ZipEntry entry) {
    return entry.isDirectory() ? Level.FINE : Level.INFO;
  }

  private static Manifest readManifest(ZipFile zip, ZipEntry manifestMfEntry) throws IOException {
    try (
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) manifestMfEntry.getSize());
        InputStream stream = zip.getInputStream(manifestMfEntry)
    ) {
      ByteStreams.copy(stream, output);
      ByteArrayInputStream rawManifest = new ByteArrayInputStream(output.toByteArray());
      return new Manifest(rawManifest);
    }
  }

  /**
   * @param directory that must not contain symlinks with loops.
   * @param jar is the file being written.
   */
  private static void addFilesInDirectoryToJar(
      Path directory,
      CustomZipOutputStream jar,
      final Set<String> alreadyAddedEntries,
      final BuckEventBus eventBus) throws IOException {

    // Since filesystem traversals can be non-deterministic, sort the entries we find into
    // a tree map before writing them out.
    final Map<String, Pair<JarEntry, Optional<Path>>> entries = Maps.newTreeMap();

    new DirectoryTraversal(directory) {

      @Override
      public void visit(Path file, String relativePath) {
        JarEntry entry = new JarEntry(relativePath.replace('\\', '/'));
        String entryName = entry.getName();
        entry.setTime(0);  // We want deterministic JARs, so avoid mtimes.

        // We expect there to be many duplicate entries for things like directories. Creating
        // those repeatedly would be lame, so don't do that.
        if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
          if (!entryName.endsWith("/")) {
            eventBus.post(ConsoleEvent.create(
                determineSeverity(entry),
                "Duplicate found when adding directory to jar: %s", relativePath));
          }
            return;
        }

        entries.put(entry.getName(), new Pair<>(entry, Optional.of(file)));
      }

      @Override
      public void visitDirectory(Path directory, String relativePath) throws IOException {
        if (relativePath.isEmpty()) {
          // root of the tree. Skip.
          return;
        }
        String entryName = relativePath.replace('\\', '/') + "/";
        if (alreadyAddedEntries.contains(entryName)) {
          return;
        }
        JarEntry entry = new JarEntry(entryName);
        entry.setTime(0);  // We want deterministic JARs, so avoid mtimes.
        entries.put(entry.getName(), new Pair<>(entry, Optional.<Path>absent()));
      }
    }.traverse();

    // Write the entries out using the iteration order of the tree map above.
    for (Pair<JarEntry, Optional<Path>> entry : entries.values()) {
      jar.putNextEntry(entry.getFirst());
      if (entry.getSecond().isPresent()) {
        Files.copy(entry.getSecond().get(), jar);
      }
      jar.closeEntry();
    }
  }

  /**
   * Merge entries from two Manifests together, with existing attributes being
   * overwritten.
   *
   * @param into The Manifest to modify.
   * @param from The Manifest to copy from.
   */
  private static void merge(Manifest into, Manifest from) {

    Attributes attributes = from.getMainAttributes();
    if (attributes != null) {
      for (Map.Entry<Object, Object> attribute : attributes.entrySet()) {
        into.getMainAttributes().put(attribute.getKey(), attribute.getValue());
      }
    }

    Map<String, Attributes> entries = from.getEntries();
    if (entries != null) {
      for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
        into.getEntries().put(entry.getKey(), entry.getValue());
      }
    }
  }

  private static boolean isDuplicateAllowed(String name) {
    return !name.endsWith(".class") && !name.endsWith("/");
  }
}

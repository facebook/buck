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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipConstants;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
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

public class JarDirectoryStepHelper {

  private JarDirectoryStepHelper() {}

  public static int createJarFile(
      ProjectFilesystem filesystem,
      Path pathToOutputFile,
      CustomZipOutputStream outputFile,
      ImmutableSortedSet<Path> entriesToJar,
      ImmutableSet<String> alreadyAddedEntriesToOutputFile,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      boolean mergeManifests,
      Iterable<Pattern> blacklist,
      JavacEventSink eventSink,
      PrintStream stdErr) throws IOException {

    Set<String> alreadyAddedEntries = Sets.newHashSet(alreadyAddedEntriesToOutputFile);

    // Write the manifest first.
    JarEntry metaInf = new JarEntry("META-INF/");
    // We want deterministic JARs, so avoid mtimes. -1 is timzeone independent, 0 is not.
    metaInf.setTime(ZipConstants.getFakeTime());
    outputFile.putNextEntry(metaInf);
    outputFile.closeEntry();
    alreadyAddedEntries.add("META-INF/");

    Manifest manifest = createManifest(
        filesystem,
        entriesToJar,
        mainClass,
        manifestFile,
        mergeManifests);
    JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
    // We want deterministic JARs, so avoid mtimes. -1 is timzeone independent, 0 is not.
    manifestEntry.setTime(ZipConstants.getFakeTime());
    outputFile.putNextEntry(manifestEntry);
    manifest.write(outputFile);
    outputFile.closeEntry();
    alreadyAddedEntries.add(JarFile.MANIFEST_NAME);

    Path absoluteOutputPath = filesystem.getPathForRelativePath(pathToOutputFile);

    for (Path entry : entriesToJar) {
      Path file = filesystem.getPathForRelativePath(entry);
      if (Files.isRegularFile(file)) {
        Preconditions.checkArgument(
            !file.equals(absoluteOutputPath),
            "Trying to put file %s into itself",
            file);
        // Assume the file is a ZIP/JAR file.
        copyZipEntriesToJar(
            file,
            pathToOutputFile,
            outputFile,
            alreadyAddedEntries,
            eventSink,
            blacklist);
      } else if (Files.isDirectory(file)) {
        addFilesInDirectoryToJar(
            filesystem,
            file,
            outputFile,
            alreadyAddedEntries,
            blacklist,
            eventSink);
      } else {
        throw new IllegalStateException("Must be a file or directory: " + file);
      }
    }

    if (mainClass.isPresent() && !mainClassPresent(mainClass.get(), alreadyAddedEntries)) {
      stdErr.print(
          String.format(
              "ERROR: Main class %s does not exist.\n",
              mainClass.get()));
      return 1;
    }

    return 0;
  }

  public static int createJarFile(
      ProjectFilesystem filesystem,
      Path pathToOutputFile,
      ImmutableSortedSet<Path> entriesToJar,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      boolean mergeManifests,
      Iterable<Pattern> blacklist,
      JavacEventSink eventSink,
      PrintStream stdErr) throws IOException {

    Path absoluteOutputPath = filesystem.getPathForRelativePath(pathToOutputFile);
    try (CustomZipOutputStream outputFile = ZipOutputStreams.newOutputStream(
        absoluteOutputPath, APPEND_TO_ZIP)) {
      return createJarFile(filesystem,
          pathToOutputFile,
          outputFile,
          entriesToJar,
          /* alreadyAddedEntriesToOutputFile */ ImmutableSet.of(),
          mainClass,
          manifestFile,
          mergeManifests,
          blacklist,
          eventSink,
          stdErr);
    }
  }

  public static int createEmptyJarFile(
      ProjectFilesystem filesystem,
      Path pathToOutputFile,
      JavacEventSink eventSink,
      PrintStream stdErr) throws IOException {
    return JarDirectoryStepHelper.createJarFile(
        filesystem,
        pathToOutputFile,
        ImmutableSortedSet.of(),
        Optional.empty(),
        Optional.empty(),
        true,
        ImmutableList.of(),
        eventSink,
        stdErr);
  }

  private static Manifest createManifest(
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> entriesToJar,
      Optional<String> mainClass,
      Optional<Path> manifestFile,
      boolean mergeManifests) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

    if (mergeManifests) {
      for (Path entry : entriesToJar) {
        entry = filesystem.getPathForRelativePath(entry);
        Manifest readManifest;
        if (Files.isDirectory(entry)) {
          Path manifestPath = entry.resolve(JarFile.MANIFEST_NAME);
          if (!Files.exists(manifestPath)) {
            continue;
          }
          try (InputStream inputStream = Files.newInputStream(manifestPath)) {
            readManifest = new Manifest(inputStream);
          }
        } else {
          // Assume a zip or jar file.
          try (ZipFile zipFile = new ZipFile(entry.toFile())) {
            ZipEntry manifestEntry = zipFile.getEntry(JarFile.MANIFEST_NAME);
            if (manifestEntry == null) {
              continue;
            }
            try (InputStream inputStream = zipFile.getInputStream(manifestEntry)) {
              readManifest = new Manifest(inputStream);
            }
          }
        }
        merge(manifest, readManifest);
      }
    }

    // Even if not merging manifests, we should include the one the user gave us. We do this last
    // so that values from the user overwrite values from merged manifests.
    if (manifestFile.isPresent()) {
      Path path = filesystem.getPathForRelativePath(manifestFile.get());
      try (InputStream stream = Files.newInputStream(path)) {
        Manifest readManifest = new Manifest(stream);
        merge(manifest, readManifest);
      }
    }

    // We may have merged manifests and over-written the user-supplied main class. Add it back.
    if (mainClass.isPresent()) {
      manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass.get());
    }

    return manifest;
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
   * @param inputFile is assumed to be a zip
   * @param outputFile the path where output is being written to
   * @param jar is the stream to write to
   * @param alreadyAddedEntries is used to avoid duplicate entries.
   */
  private static void copyZipEntriesToJar(
      Path inputFile,
      Path outputFile,
      final CustomZipOutputStream jar,
      Set<String> alreadyAddedEntries,
      JavacEventSink eventSink,
      Iterable<Pattern> blacklist) throws IOException {
    try (ZipFile zip = new ZipFile(inputFile.toFile())) {
      for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
        ZipEntry entry = entries.nextElement();
        String entryName = entry.getName();

        // We already read the manifest. No need to read it again
        if (JarFile.MANIFEST_NAME.equals(entryName)) {
          continue;
        }

        // Check if the entry belongs to the blacklist and it should be excluded from the Jar.
        if (shouldEntryBeRemovedFromJar(eventSink, entryName, blacklist)) {
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
          eventSink.reportEvent(
                  determineSeverity(entry),
                  "Duplicate found when adding '%s' to '%s' from '%s'",
                  entryName,
                  outputFile.toAbsolutePath(),
                  inputFile.toAbsolutePath());
          continue;
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
        try (InputStream inputStream = zip.getInputStream(entry)) {
          ByteStreams.copy(inputStream, jar);
        }
        jar.closeEntry();
      }
    } catch (ZipException e) {
      throw new IOException(
          "Failed to process zip file " + inputFile + ": " + e.getMessage(), e);
    }
  }

  private static Level determineSeverity(ZipEntry entry) {
    return entry.isDirectory() ? Level.FINE : Level.INFO;
  }

  /**
   * @param directory that must not contain symlinks with loops.
   * @param jar is the file being written.
   */
  private static void addFilesInDirectoryToJar(
      final ProjectFilesystem filesystem,
      final Path directory,
      CustomZipOutputStream jar,
      final Set<String> alreadyAddedEntries,
      final Iterable<Pattern> blacklist,
      final JavacEventSink eventSink) throws IOException {

    // Since filesystem traversals can be non-deterministic, sort the entries we find into
    // a tree map before writing them out.
    final Map<String, Pair<JarEntry, Optional<Path>>> entries = Maps.newTreeMap();

    filesystem.walkFileTree(
        directory,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String relativePath =
                MorePaths.pathWithUnixSeparators(MorePaths.relativize(directory, file));

            // Skip re-reading the manifest
            if (JarFile.MANIFEST_NAME.equals(relativePath)) {
              return FileVisitResult.CONTINUE;
            }

            // Check if the entry belongs to the blacklist and it should be excluded from the Jar.
            if (shouldEntryBeRemovedFromJar(eventSink, relativePath, blacklist)) {
              return FileVisitResult.CONTINUE;
            }

            JarEntry entry = new JarEntry(relativePath);
            String entryName = entry.getName();
            // We want deterministic JARs, so avoid mtimes.
            entry.setTime(ZipConstants.getFakeTime());

            // We expect there to be many duplicate entries for things like directories. Creating
            // those repeatedly would be lame, so don't do that.
            if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
              if (!entryName.endsWith("/")) {
                eventSink.reportEvent(
                    determineSeverity(entry),
                    "Duplicate found when adding directory to jar: %s", relativePath);
              }
              return FileVisitResult.CONTINUE;
            }

            entries.put(entry.getName(), new Pair<>(entry, Optional.of(file)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            String relativePath =
                MorePaths.pathWithUnixSeparators(MorePaths.relativize(directory, dir));
            if (relativePath.isEmpty()) {
              // root of the tree. Skip.
              return FileVisitResult.CONTINUE;
            }
            String entryName = relativePath.replace('\\', '/') + "/";
            if (alreadyAddedEntries.contains(entryName)) {
              return FileVisitResult.CONTINUE;
            }
            JarEntry entry = new JarEntry(entryName);
            // We want deterministic JARs, so avoid mtimes.
            entry.setTime(ZipConstants.getFakeTime());
            entries.put(entry.getName(), new Pair<>(entry, Optional.empty()));
            return FileVisitResult.CONTINUE;
          }
        });

    // Write the entries out using the iteration order of the tree map above.
    for (Pair<JarEntry, Optional<Path>> entry : entries.values()) {
      jar.putNextEntry(entry.getFirst());
      if (entry.getSecond().isPresent()) {
        Files.copy(entry.getSecond().get(), jar);
      }
      jar.closeEntry();
    }
  }

  private static boolean shouldEntryBeRemovedFromJar(
      JavacEventSink eventSink,
      String relativePath,
      Iterable<Pattern> blacklist) {
    String entry = relativePath;
    if (relativePath.contains(".class")) {
      entry = relativePath.replace('/', '.').replace(".class", "");
    }
    for (Pattern pattern : blacklist) {
      if (pattern.matcher(entry).find()) {
        eventSink.reportEvent(Level.FINE, "%s is excluded from the Jar.", entry);
        return true;
      }
    }
    return false;
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
        Attributes existing = into.getAttributes(entry.getKey());
        if (existing == null) {
          existing = new Attributes();
          into.getEntries().put(entry.getKey(), existing);
        }
        existing.putAll(entry.getValue());
      }
    }
  }

  private static boolean isDuplicateAllowed(String name) {
    return !name.endsWith(".class") && !name.endsWith("/");
  }
}

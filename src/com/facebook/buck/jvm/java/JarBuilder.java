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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.zip.CustomJarOutputStream;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.DeterministicManifest;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import javax.annotation.Nullable;

public class JarBuilder {
  private final ProjectFilesystem filesystem;
  private final JavacEventSink eventSink;
  private final PrintStream stdErr;

  @Nullable private Path outputFile;
  @Nullable private CustomJarOutputStream jar;
  @Nullable private String mainClass;
  @Nullable private Path manifestFile;
  private boolean shouldMergeManifests;
  private boolean shouldHashEntries;
  private Iterable<Pattern> blacklist = new ArrayList<>();
  private List<JarEntryContainer> sourceContainers = new ArrayList<>();
  private Set<String> alreadyAddedEntries = new HashSet<>();

  public JarBuilder(ProjectFilesystem filesystem, JavacEventSink eventSink, PrintStream stdErr) {
    this.filesystem = filesystem;
    this.eventSink = eventSink;
    this.stdErr = stdErr;
  }

  public JarBuilder setEntriesToJar(ImmutableSortedSet<Path> entriesToJar) {
    sourceContainers.clear();

    entriesToJar
        .stream()
        .map(filesystem::getPathForRelativePath)
        .map(JarEntryContainer::of)
        .forEach(sourceContainers::add);

    return this;
  }

  public JarBuilder addEntryContainer(JarEntryContainer container) {
    sourceContainers.add(container);
    return this;
  }

  public JarBuilder setAlreadyAddedEntries(ImmutableSet<String> alreadyAddedEntries) {
    alreadyAddedEntries.forEach(this.alreadyAddedEntries::add);
    return this;
  }

  public JarBuilder setMainClass(String mainClass) {
    this.mainClass = mainClass;
    return this;
  }

  public JarBuilder setManifestFile(Path manifestFile) {
    this.manifestFile = manifestFile;
    return this;
  }

  public JarBuilder setShouldMergeManifests(boolean shouldMergeManifests) {
    this.shouldMergeManifests = shouldMergeManifests;
    return this;
  }

  public JarBuilder setShouldHashEntries(boolean shouldHashEntries) {
    this.shouldHashEntries = shouldHashEntries;
    return this;
  }

  public JarBuilder setEntryPatternBlacklist(Iterable<Pattern> blacklist) {
    this.blacklist = blacklist;
    return this;
  }

  public int createJarFile(Path outputFile) throws IOException {
    Path absoluteOutputPath = filesystem.getPathForRelativePath(outputFile);
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(absoluteOutputPath, APPEND_TO_ZIP)) {
      jar.setEntryHashingEnabled(shouldHashEntries);
      return appendToJarFile(outputFile, jar);
    }
  }

  public int appendToJarFile(Path relativeOutputFile, CustomJarOutputStream jar)
      throws IOException {
    this.outputFile = filesystem.getPathForRelativePath(relativeOutputFile);
    this.jar = jar;

    // Write the manifest first.
    writeManifest();

    for (JarEntryContainer sourceContainer : sourceContainers) {
      addEntriesToJar(sourceContainer);
    }

    if (mainClass != null && !mainClassPresent()) {
      stdErr.print(String.format("ERROR: Main class %s does not exist.\n", mainClass));
      return 1;
    }

    return 0;
  }

  private void writeManifest() throws IOException {
    DeterministicManifest manifest = jar.getManifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

    if (shouldMergeManifests) {
      for (JarEntryContainer sourceContainer : sourceContainers) {
        Manifest readManifest = sourceContainer.getManifest();
        if (readManifest != null) {
          merge(manifest, readManifest);
        }
      }
    }

    // Even if not merging manifests, we should include the one the user gave us. We do this last
    // so that values from the user overwrite values from merged manifests.
    if (manifestFile != null) {
      Path path = filesystem.getPathForRelativePath(manifestFile);
      try (InputStream stream = Files.newInputStream(path)) {
        Manifest readManifest = new Manifest(stream);
        merge(manifest, readManifest);
      }
    }

    // We may have merged manifests and over-written the user-supplied main class. Add it back.
    if (mainClass != null) {
      manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
    }

    jar.writeManifest();
  }

  private boolean mainClassPresent() {
    String mainClassPath = classNameToPath(mainClass);

    return alreadyAddedEntries.contains(mainClassPath);
  }

  private String classNameToPath(String className) {
    return className.replace('.', '/') + ".class";
  }

  private Level determineSeverity(ZipEntry entry) {
    return entry.isDirectory() ? Level.FINE : Level.INFO;
  }

  private void addEntriesToJar(JarEntryContainer container) throws IOException {
    Iterable<JarEntrySupplier> entries = container.stream()::iterator;
    for (JarEntrySupplier entrySupplier : entries) {
      addEntryToJar(entrySupplier);
    }
  }

  private void addEntryToJar(JarEntrySupplier entrySupplier) throws IOException {
    CustomZipEntry entry = entrySupplier.getEntry();
    String entryName = entry.getName();

    // We already read the manifest. No need to read it again
    if (JarFile.MANIFEST_NAME.equals(entryName)) {
      return;
    }

    // Check if the entry belongs to the blacklist and it should be excluded from the Jar.
    if (shouldEntryBeRemovedFromJar(entryName)) {
      return;
    }

    // We're in the process of merging a bunch of different jar files. These typically contain
    // just ".class" files and the manifest, but they can also include things like license files
    // from third party libraries and config files. We should include those license files within
    // the jar we're creating. Extracting them is left as an exercise for the consumer of the
    // jar.  Because we don't know which files are important, the only ones we skip are
    // duplicate class files.
    if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
      if (!entryName.endsWith("/")) {
        // Duplicate entries. Skip.
        eventSink.reportEvent(
            determineSeverity(entry),
            "Duplicate found when adding '%s' to '%s' from '%s'",
            entryName,
            outputFile,
            entrySupplier.getEntryOwner());
      }
      return;
    }

    jar.putNextEntry(entry);
    try (InputStream entryInputStream = entrySupplier.getInputStreamSupplier().get()) {
      if (entryInputStream != null) {
        // Null stream means a directory
        ByteStreams.copy(entryInputStream, jar);
      }
    }
    jar.closeEntry();
  }

  private boolean shouldEntryBeRemovedFromJar(String relativePath) {
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
   * Merge entries from two Manifests together, with existing attributes being overwritten.
   *
   * @param into The Manifest to modify.
   * @param from The Manifest to copy from.
   */
  private void merge(Manifest into, Manifest from) {

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

  private boolean isDuplicateAllowed(String name) {
    return !name.endsWith(".class") && !name.endsWith("/");
  }
}

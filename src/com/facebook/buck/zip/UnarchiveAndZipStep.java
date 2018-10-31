/*
 * Copyright 2018-present Facebook, Inc.
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

import static com.facebook.buck.util.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.PatternsMatcher;
import com.facebook.buck.util.filesystem.SourcePathToPathResolver;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.zip.CustomZipEntry;
import com.facebook.buck.util.zip.CustomZipOutputStream;
import com.facebook.buck.util.zip.Zip;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.util.zip.ZipOutputStreams;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** A {@link com.facebook.buck.step.Step} that creates a ZIP archive from source files and zips. */
public class UnarchiveAndZipStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path basePath;
  private final Path pathToZipFile;
  private final boolean junkPaths;
  private final ZipCompressionLevel compressionLevel;
  private final ImmutableSortedSet<SourcePath> sources;
  private final ImmutableSortedSet<SourcePath> zipSources;
  private final Boolean mergeSourceZips;
  private final PatternsMatcher entriesToExclude;
  private final SourcePathResolver pathResolver;

  /**
   * Create a {@link UnarchiveAndZipStep} to read from source files or zip files and write directly
   * into a zip archive.
   *
   * <p>Note that paths added to the archive are always relative to the working directory.
   *
   * <p>For example, if you're in {@code /dir} and you add {@code file.txt}, you get an archive
   * containing just the file. If you were in {@code /} and added {@code dir/file.txt}, you would
   * get an archive containing the file within a directory. Sources and zip sources to include must
   * be explicitly included.
   *
   * @param filesystem {@link ProjectFilesystem} project filesystem based in current working
   *     directory.
   * @param basePath working directory.
   * @param pathToZipFile output file for zip relative to the current working directory.
   * @param sources source files to add to the output zip.
   * @param zipSources zip files whose contents are added to the output zip.
   * @param junkPaths flatten the path to the source file.
   * @param mergeSourceZips if true then the contents of zip files listed in sources will be copied,
   *     otherwise, the entire file will be copied.
   * @param pathResolver resolves source paths to files.
   * @param entriesToExclude entries to exclude from the final zip. This only applies to names of
   *     {@code sources} or {@code zipSources} and not the files located within {@code zipSources}.
   * @param compressionLevel compression level of zip sources or zip sources.
   */
  public UnarchiveAndZipStep(
      ProjectFilesystem filesystem,
      Path basePath,
      Path pathToZipFile,
      ImmutableSortedSet<SourcePath> sources,
      ImmutableSortedSet<SourcePath> zipSources,
      boolean junkPaths,
      Boolean mergeSourceZips,
      SourcePathResolver pathResolver,
      PatternsMatcher entriesToExclude,
      ZipCompressionLevel compressionLevel) {
    this.filesystem = filesystem;
    this.basePath = basePath;
    this.pathToZipFile = pathToZipFile;
    this.mergeSourceZips = mergeSourceZips;
    this.compressionLevel = compressionLevel;
    this.sources = sources;
    this.zipSources = zipSources;
    this.junkPaths = junkPaths;
    this.entriesToExclude = entriesToExclude;
    this.pathResolver = pathResolver;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    if (filesystem.exists(pathToZipFile)) {
      context.postEvent(
          ConsoleEvent.severe("Attempting to overwrite an existing zip: %s", pathToZipFile));
      return StepExecutionResults.ERROR;
    }

    ImmutableMap<Path, Path> relativeMapSources =
        SourcePathToPathResolver.createRelativeMap(basePath, filesystem, pathResolver, sources);

    Map<Path, Path> relativeMapZipSources =
        Maps.newHashMap(
            SourcePathToPathResolver.createRelativeMap(
                basePath, filesystem, pathResolver, zipSources));

    ImmutableMap<Path, Pair<Boolean, List<Path>>> baseDirsToSearch = getBaseDirectoriesToSearch();

    populateBaseDirectorySearch(relativeMapSources, relativeMapZipSources, baseDirsToSearch);

    ImmutableSortedMap.Builder<String, Pair<CustomZipEntry, Optional<Path>>> entries =
        ImmutableSortedMap.naturalOrder();

    walkTreeDirectoriesForFiles(entries, baseDirsToSearch);

    // Copy the files to the output zip.
    try (BufferedOutputStream baseOut =
            new BufferedOutputStream(filesystem.newFileOutputStream(pathToZipFile));
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(baseOut, APPEND_TO_ZIP)) {
      ImmutableSortedMap<String, Pair<CustomZipEntry, Optional<Path>>> sourcesMap = entries.build();
      Zip.writeEntriesToZip(filesystem, out, sourcesMap);
      writeZipSourcesToZip(out, relativeMapZipSources, new HashSet<String>(sourcesMap.keySet()));
    }
    return StepExecutionResults.SUCCESS;
  }

  private ImmutableMap<Path, Pair<Boolean, List<Path>>> getBaseDirectoriesToSearch()
      throws IOException {
    ImmutableMap.Builder<Path, Pair<Boolean, List<Path>>> baseDirsToSearch = ImmutableMap.builder();

    // Search for source files in the current working directory
    baseDirsToSearch.put(basePath, new Pair<Boolean, List<Path>>(junkPaths, new ArrayList<Path>()));

    // Get the generated directory files for sources and zip sources generated by buck in previous
    // steps.
    Path generatedDirectory = filesystem.getBuckPaths().getGenDir();
    if (filesystem.exists(generatedDirectory)) {
      ImmutableCollection<Path> files = filesystem.getDirectoryContents(generatedDirectory);
      for (Path file : files) {
        // Make sure junkPaths is set to true because we don't want to prepend the file name
        // with the zip it is located in.
        baseDirsToSearch.put(file, new Pair<Boolean, List<Path>>(true, new ArrayList<>()));
      }
    }
    return baseDirsToSearch.build();
  }

  private void populateBaseDirectorySearch(
      ImmutableMap<Path, Path> relativeMapSources,
      Map<Path, Path> relativeMapZipSources,
      Map<Path, Pair<Boolean, List<Path>>> baseDirsToSearch) {

    // Build the set of relative file paths to the source files
    // and remove the zips listed in the source files.
    for (Map.Entry<Path, Path> pathEntry : relativeMapSources.entrySet()) {
      Path relativePath = pathEntry.getKey();

      // Remove the sources to exclude.
      String entryName = MorePaths.pathWithUnixSeparators(relativePath);
      if (entriesToExclude.matchesAny(entryName)) {
        continue;
      }

      // If the source file is a zip/jar and we want to unpack then add to zip sources.
      if ((relativePath.toString().endsWith(Javac.SRC_ZIP)
              || relativePath.toString().endsWith(Javac.SRC_JAR))
          && mergeSourceZips) {
        relativeMapZipSources.put(relativePath, pathEntry.getValue());
      }

      // Add the source entry to base directory that will be searched.
      Path filesystemRoot = filesystem.getRootPath();
      for (Map.Entry<Path, Pair<Boolean, List<Path>>> entry : baseDirsToSearch.entrySet()) {
        if (filesystemRoot.relativize(pathEntry.getValue()).startsWith(entry.getKey())) {
          entry.getValue().getSecond().add(pathEntry.getValue());
        }
      }
    }
  }
  /**
   * Walks each base directory {@code baseDirsToSearch} if there are paths that are found in them
   */
  private void walkTreeDirectoriesForFiles(
      ImmutableSortedMap.Builder<String, Pair<CustomZipEntry, Optional<Path>>> entries,
      ImmutableMap<Path, Pair<Boolean, List<Path>>> baseDirsToSearch)
      throws IOException {
    for (Map.Entry<Path, Pair<Boolean, List<Path>>> entry : baseDirsToSearch.entrySet()) {
      if (!entry.getValue().getSecond().isEmpty()) {
        Zip.walkBaseDirectoryToCreateEntries(
            filesystem,
            entries,
            entry.getKey(),
            ImmutableSet.copyOf(entry.getValue().getSecond()),
            entry.getValue().getFirst(),
            compressionLevel,
            true);
      }
    }
  }

  private void writeZipSourcesToZip(
      CustomZipOutputStream zipOut,
      Map<Path, Path> relativeMapZipSources,
      Set<String> existingEntries)
      throws IOException {
    for (Map.Entry<Path, Path> pathEntry : relativeMapZipSources.entrySet()) {
      Path archiveFile = Objects.requireNonNull(pathEntry.getValue());
      try (ZipFile zip = new ZipFile(archiveFile.toFile())) {
        for (Enumeration<ZipArchiveEntry> e = zip.getEntries(); e.hasMoreElements(); ) {
          ZipArchiveEntry entry = e.nextElement();

          // Do not include entry if duplicate one is already written to disk
          if (entriesToExclude.matchesAny(entry.getName())
              || existingEntries.contains(entry.getName())) {
            continue;
          }
          existingEntries.add(entry.getName());

          CustomZipEntry zipOutEntry = Zip.getZipEntry(zip, entry, compressionLevel);
          zipOut.putNextEntry(zipOutEntry);
          try (InputStream is = zip.getInputStream(entry)) {
            ByteStreams.copy(is, zipOut);
          }
        }
        zipOut.closeEntry();
      }
    }
  }

  @Override
  public String getShortName() {
    return "unzip+zip";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder args = new StringBuilder("zip ");

    // Don't add extra fields, neither do the Android tools.
    args.append("-X ");

    // recurse
    args.append("-r ");

    // compression level
    args.append("-").append(compressionLevel).append(" ");

    // junk paths
    if (junkPaths) {
      args.append("-j ");
    }

    // destination archive
    args.append(pathToZipFile).append(" ");

    return args.toString();
  }
}

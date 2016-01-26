/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.OVERWRITE_EXISTING;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.MorePosixFilePermissions;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.Pair;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

/**
 * A {@link com.facebook.buck.step.Step} that creates a ZIP archive..
 */
@SuppressWarnings("PMD.AvoidUsingOctalValues")
public class ZipStep implements Step {

  private static final Logger LOG = Logger.get(ZipStep.class);

  public static final int MIN_COMPRESSION_LEVEL = 0;
  public static final int DEFAULT_COMPRESSION_LEVEL = 6;
  public static final int MAX_COMPRESSION_LEVEL = 9;

  // Extended attribute bits for directories and symlinks; see:
  // http://unix.stackexchange.com/questions/14705/the-zip-formats-external-file-attribute
  public static final long S_IFDIR = 0040000;
  public static final long S_IFLNK = 0120000;

  private final ProjectFilesystem filesystem;
  private final Path pathToZipFile;
  private final ImmutableSet<Path> paths;
  private final boolean junkPaths;
  private final int compressionLevel;
  private final Path baseDir;


  /**
   * Create a {@link ZipStep} to create or update a zip archive.
   *
   * Note that paths added to the archive are always relative to the working directory.<br>
   * For example, if you're in {@code /dir} and you add {@code file.txt}, you get
   * an archive containing just the file. If you were in {@code /} and added
   * {@code dir/file.txt}, you would get an archive containing the file within a directory.
   *
   * @param pathToZipFile path to archive to create, relative to project root.
   * @param paths a set of files to work on. The entire working directory is assumed if this set
   *    is empty.
   * @param junkPaths if {@code true}, the relative paths of added archive entries are discarded,
   *    i.e. they are all placed in the root of the archive.
   * @param compressionLevel between 0 (store) and 9.
   * @param baseDir working directory for {@code zip} command.
   */
  public ZipStep(
      ProjectFilesystem filesystem,
      Path pathToZipFile,
      Set<Path> paths,
      boolean junkPaths,
      int compressionLevel,
      Path baseDir) {
    Preconditions.checkArgument(compressionLevel >= MIN_COMPRESSION_LEVEL &&
        compressionLevel <= MAX_COMPRESSION_LEVEL, "compressionLevel out of bounds.");
    this.filesystem = filesystem;
    this.pathToZipFile = pathToZipFile;
    this.paths = ImmutableSet.copyOf(paths);
    this.junkPaths = junkPaths;
    this.compressionLevel = compressionLevel;
    this.baseDir = baseDir;
  }

  @Override
  public int execute(ExecutionContext context) {
    if (filesystem.exists(pathToZipFile)) {
      context.postEvent(
          ConsoleEvent.severe("Attempting to overwrite an existing zip: %s", pathToZipFile));
      return 1;
    }

    // Since filesystem traversals can be non-deterministic, sort the entries we find into
    // a tree map before writing them out.
    final Map<String, Pair<CustomZipEntry, Optional<Path>>> entries = Maps.newTreeMap();

    FileVisitor<Path> pathFileVisitor = new SimpleFileVisitor<Path>() {
      private boolean isSkipFile(Path file) {
        return !paths.isEmpty() && !paths.contains(file);
      }

      private String getEntryName(Path path) {
        Path relativePath = junkPaths ? path.getFileName() : baseDir.relativize(path);
        return MorePaths.pathWithUnixSeparators(relativePath);
      }

      private CustomZipEntry getZipEntry(
          String entryName,
          final Path path,
          BasicFileAttributes attr) throws IOException {
        boolean isDirectory = filesystem.isDirectory(path);
        if (isDirectory) {
          entryName += "/";
        }

        CustomZipEntry entry = new CustomZipEntry(entryName);
        entry.setTime(0);  // We want deterministic ZIP files, so avoid mtimes.
        entry.setCompressionLevel(isDirectory ? MIN_COMPRESSION_LEVEL : compressionLevel);
        if (entry.getCompressionLevel() == MIN_COMPRESSION_LEVEL) {
          entry.setMethod(ZipEntry.STORED);
        }
        // If we're using STORED files, we must manually set the CRC, size, and compressed size.
        if (entry.getMethod() == ZipEntry.STORED && !isDirectory) {
          entry.setSize(attr.size());
          entry.setCompressedSize(attr.size());
          entry.setCrc(
              new ByteSource() {
                @Override
                public InputStream openStream() throws IOException {
                  return filesystem.newFileInputStream(path);
                }
              }.hash(Hashing.crc32()).padToLong());
        }

        long mode = 0;
        // Support executable files.  If we detect this file is executable, store this
        // information as 0100 in the field typically used in zip implementations for
        // POSIX file permissions.  We'll use this information when unzipping.
        if (filesystem.isExecutable(path)) {
          mode |=
              MorePosixFilePermissions.toMode(
                  EnumSet.of(PosixFilePermission.OWNER_EXECUTE));
        }

        if (isDirectory) {
          mode |= S_IFDIR;
        }

        if (filesystem.isSymLink(path)) {
          mode |= S_IFLNK;
        }

        // Propagate any additional permissions
        mode |= MorePosixFilePermissions.toMode(filesystem.getPosixFilePermissions(path));

        long externalAttributes = mode << 16;
        LOG.verbose(
            "Setting mode for entry %s path %s to 0x%08X (0x%08X)",
            entryName, path, mode, externalAttributes);
        entry.setExternalAttributes(externalAttributes);
        return entry;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
          throws IOException {
        if (!isSkipFile(file)) {
          CustomZipEntry entry = getZipEntry(getEntryName(file), file, attrs);
          entries.put(entry.getName(), new Pair<>(entry, Optional.of(file)));
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        if (!dir.equals(baseDir) && !isSkipFile(dir)) {
          CustomZipEntry entry = getZipEntry(getEntryName(dir), dir, attrs);
          entries.put(entry.getName(), new Pair<>(entry, Optional.<Path>absent()));
        }
        return FileVisitResult.CONTINUE;
      }
    };

    try (
      BufferedOutputStream baseOut =
          new BufferedOutputStream(filesystem.newFileOutputStream(pathToZipFile));
      CustomZipOutputStream out =
          ZipOutputStreams.newOutputStream(baseOut, OVERWRITE_EXISTING)) {

      filesystem.walkRelativeFileTree(baseDir, pathFileVisitor);

      // Write the entries out using the iteration order of the tree map above.
      for (Pair<CustomZipEntry, Optional<Path>> entry : entries.values()) {
        out.putNextEntry(entry.getFirst());
        if (entry.getSecond().isPresent()) {
          try (InputStream input = filesystem.newFileInputStream(entry.getSecond().get())) {
            ByteStreams.copy(input, out);
          }
        }
        out.closeEntry();
      }

    } catch (IOException e) {
      context.logError(e, "Error creating zip file %s", pathToZipFile);
      return 1;
    }

    return 0;
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

    // unk paths
    if (junkPaths) {
      args.append("-j ");
    }

    // destination archive
    args.append(pathToZipFile.toString()).append(" ");

    // files to add to archive
    if (paths.isEmpty()) {
      // Add the contents of workingDirectory to archive.
      args.append("-i* ");
      args.append(". ");
    } else {
      // Add specified paths, relative to workingDirectory.
      for (Path path : paths) {
        args.append(path.toString()).append(" ");
      }
    }

    return args.toString();
  }

  @Override
  public String getShortName() {
    return "zip";
  }

}

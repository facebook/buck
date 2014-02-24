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
import static java.util.logging.Level.SEVERE;

import com.facebook.buck.event.LogEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.DirectoryTraversal;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

/**
 * A {@link com.facebook.buck.step.Step} that creates a ZIP archive..
 */
public class ZipStep implements Step {

  public static final int MIN_COMPRESSION_LEVEL = 0;
  public static final int DEFAULT_COMPRESSION_LEVEL = 6;
  public static final int MAX_COMPRESSION_LEVEL = 9;

  private final String absolutePathToZipFile;
  private final ImmutableSet<String> paths;
  private final boolean junkPaths;
  private final int compressionLevel;
  private final File baseDir;


  /**
   * Create a {@link ZipStep} to create or update a zip archive.
   *
   * Note that paths added to the archive are always relative to the working directory.<br/>
   * For example, if you're in {@code /dir} and you add {@code file.txt}, you get
   * an archive containing just the file. If you were in {@code /} and added
   * {@code dir/file.txt}, you would get an archive containing the file within a directory.
   *
   *
   * @param absolutePathToZipFile path to archive to create.
   * @param paths a set of files and/or directories to work on. The entire working directory is
   *    assumed if this set is empty.
   * @param junkPaths if {@code true}, the relative paths of added archive entries are discarded,
   *    i.e. they are all placed in the root of the archive.
   * @param compressionLevel between 0 (store) and 9.
   * @param baseDir working directory for {@code zip} command.
   *    If {@code null}, project directory root is used instead.
   */
  public ZipStep(
      String absolutePathToZipFile,
      Set<String> paths,
      boolean junkPaths,
      int compressionLevel,
      File baseDir) {
    Preconditions.checkArgument(compressionLevel >= MIN_COMPRESSION_LEVEL &&
        compressionLevel <= MAX_COMPRESSION_LEVEL, "compressionLevel out of bounds.");
    this.absolutePathToZipFile = Preconditions.checkNotNull(absolutePathToZipFile);
    this.paths = ImmutableSet.copyOf(Preconditions.checkNotNull(paths));
    this.junkPaths = junkPaths;
    this.compressionLevel = compressionLevel;
    this.baseDir = Preconditions.checkNotNull(baseDir);
  }


  @Override
  public int execute(ExecutionContext context) {
    File original = new File(absolutePathToZipFile);
    if (original.exists()) {
      context.getBuckEventBus().post(
          LogEvent.create(SEVERE, "Attempting to overwrite an existing zip: %s", original)
      );
      return 1;
    }

    try (
      BufferedOutputStream baseOut =
          new BufferedOutputStream(new FileOutputStream(absolutePathToZipFile));
      CustomZipOutputStream out = ZipOutputStreams.newOutputStream(baseOut, OVERWRITE_EXISTING);
    ) {
      DirectoryTraversal traversal = new DirectoryTraversal(baseDir) {

        @Override
        public void visit(File file, String relativePath) throws IOException {
          if (!paths.isEmpty() && !paths.contains(relativePath)) {
            return;
          }

          String name = junkPaths ? file.getName() : relativePath;
          if (file.isDirectory()) {
            // Lame.
            name += "/";
          }

          CustomZipEntry entry = new CustomZipEntry(name);
          entry.setTime(file.lastModified());
          entry.setSize(file.length());
          entry.setCompressionLevel(compressionLevel);

          out.putNextEntry(entry);
          Files.copy(file.toPath(), out);
          out.closeEntry();
        }
      };

      traversal.traverse();

      return 0;
    } catch (IOException e) {
      return 1;
    }

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
    args.append(absolutePathToZipFile).append(" ");

    // files to add to archive
    if (paths.isEmpty()) {
      // Add the contents of workingDirectory to archive.
      args.append("-i* ");
      args.append(". ");
    } else {
      // Add specified paths, relative to workingDirectory.
      for (String path : paths) {
        args.append(path).append(" ");
      }
    }

    return args.toString();
  }

  @Override
  public String getShortName() {
    return "zip";
  }

}

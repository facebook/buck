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

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.util.Set;

/**
 * A {@link com.facebook.buck.step.Step} that creates or updates a ZIP archive using {@code zip}.
 *
 * @see <a href="http://www.info-zip.org/mans/zip.html">ZIP</a>
 */
public class ZipStep extends ShellStep {

  public static final int MIN_COMPRESSION_LEVEL = 0;
  public static final int DEFAULT_COMPRESSION_LEVEL = 6;
  public static final int MAX_COMPRESSION_LEVEL = 9;

  /**
   * These map to {@code zip} 'command modes' (as seen in man page).
   * @see <a href="http://www.info-zip.org/mans/zip.html">ZIP</a>
   */
  public static enum Mode {
    /**
     * Update existing entries and add new files.
     * If the archive does not exist create it. This is the default mode.
     */
    ADD(""),
    /**
     * Update existing entries if newer on the file system and add new files.
     * If the archive does not exist issue warning then create a new archive.
     */
    UPDATE("-u"),
    /**
     * Update existing entries of an archive if newer on the file system.
     * Does not add new files to the archive.
     */
    FRESHEN("-f"),
    /**
     * Select entries in an existing archive and delete them.
     */
    DELETE("-d");

    public final String arg;

    private Mode(String arg) {
      this.arg = arg;
    }
  }

  private final Mode mode;
  private final String absolutePathToZipFile;
  private final ImmutableSet<String> paths;
  private final boolean junkPaths;
  private final int compressionLevel;


  /**
   * Create a {@link ZipStep} to create or update a zip archive.
   *
   * Note that paths added to the archive are always relative to the working directory.<br/>
   * For example, if you're in {@code /dir} and you add {@code file.txt}, you get
   * an archive containing just the file. If you were in {@code /} and added
   * {@code dir/file.txt}, you would get an archive containing the file within a directory.
   *
   * @param mode one of {@link ZipStep.Mode#ADD}, {@link ZipStep.Mode#UPDATE UPDATE},
   *    {@link ZipStep.Mode#FRESHEN FRESHEN} or {@link ZipStep.Mode#DELETE DELETE}, as in the
   *    {@code zip} command.
   * @param absolutePathToZipFile path to archive to create or update
   * @param paths a set of files and/or directories to work on. The entire working directory is
   *    assumed if this set is empty.
   * @param junkPaths if {@code true}, the relative paths of added archive entries are discarded,
   *    i.e. they are all placed in the root of the archive.
   * @param compressionLevel between 0 (store) and 9.
   * @param workingDirectory working directory for {@code zip} command.
   *    If {@code null}, project directory root is used instead.
   *
   * @see <a href="http://www.info-zip.org/mans/zip.html">ZIP</a>
   */
  public ZipStep(
      Mode mode,
      String absolutePathToZipFile,
      Set<String> paths,
      boolean junkPaths,
      int compressionLevel,
      File workingDirectory) {
    super(workingDirectory);
    Preconditions.checkArgument(compressionLevel >= MIN_COMPRESSION_LEVEL &&
        compressionLevel <= MAX_COMPRESSION_LEVEL, "compressionLevel out of bounds.");
    this.mode = mode;
    this.absolutePathToZipFile = Preconditions.checkNotNull(absolutePathToZipFile);
    this.paths = ImmutableSet.copyOf(Preconditions.checkNotNull(paths));
    this.junkPaths = junkPaths;
    this.compressionLevel = compressionLevel;
  }

  /**
   * Create a {@link ZipStep} that adds an entire directory to an archive. The files directly in
   * the directory will appear in the root of the archive. Default compression level is assumed.
   *
   * @param zipFile file to archive to create or update
   * @param directoryToAdd directory to add to the archive
   *
   * @see #ZipStep(Mode, String, Set, boolean, int, File)
   */
  public ZipStep(File zipFile, File directoryToAdd) {
    this(
        Mode.ADD,
        zipFile.getAbsolutePath(),
        ImmutableSet.<String>of() /* pathsToAdd */,
        false /* junkPaths */,
        DEFAULT_COMPRESSION_LEVEL /* compressionLevel */,
        Preconditions.checkNotNull(directoryToAdd));
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("zip");

    // What to do?
    if (mode != Mode.ADD) {
      args.add(mode.arg);
    }

    Verbosity verbosity = context.getVerbosity();
    if (!verbosity.shouldUseVerbosityFlagIfAvailable()) {
      if (verbosity.shouldPrintStandardInformation()) {
        args.add("-q");
      } else {
        args.add("-qq");
      }
    }

    // Don't add extra fields, neither do the Android tools.
    args.add("-X");

    // recurse
    args.add("-r");

    // compression level
    args.add("-" + compressionLevel);

    // unk paths
    if (junkPaths) {
      args.add("-j");
    }

    // destination archive
    args.add(absolutePathToZipFile);

    // files to add to archive
    if (paths.isEmpty()) {
      // Add the contents of workingDirectory to archive.
      args.add("-i*");
      args.add(".");
    } else {
      // Add specified paths, relative to workingDirectory.
      args.addAll(paths);
    }

    return args.build();
  }

  @Override
  public String getShortName() {
    return "zip";
  }

}

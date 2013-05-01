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

package com.facebook.buck.android;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.Paths;
import com.facebook.buck.util.ZipSplitter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Split zipping tool designed to divide input code blobs into a set of output jar files such that
 * none will exceed the DexOpt LinearAlloc limit or the dx method limit when passed through
 * dx --dex.
 */
public class SplitZipStep implements Step {

  private static final int ZIP_SIZE_SOFT_LIMIT = 12 * 1024 * 1024;

  /**
   * The uncompressed class size is a very simple metric that we can use to roughly estimate
   * whether we will hit the DexOpt LinearAlloc limit.  When we hit the limit, we were around
   * 20 MB uncompressed, so use 14 MB as a safer upper limit.
   */
  private static final int ZIP_SIZE_HARD_LIMIT = ZIP_SIZE_SOFT_LIMIT + (2 * 1024 * 1024);

  @VisibleForTesting
  static final Pattern classFilePattern = Pattern.compile("^([\\w/$]+)\\.class");

  private final Set<String> inputPathsToSplit;
  private final String secondaryJarMetaPath;
  private final String primaryJarPath;
  private final String secondaryJarDir;
  private final String secondaryJarPattern;
  private final ImmutableSet<String> primaryDexSubstrings;
  private final Predicate<String> requiredInPrimaryZip;
  private final ZipSplitter.DexSplitStrategy dexSplitStrategy;
  private final DexStore dexStore;

  /**
   * @param inputPathsToSplit Input paths that would otherwise have been passed to a single dx --dex
   *     invocation.
   * @param secondaryJarMetaPath Output location for the metadata text file describing each
   *     secondary jar artifact.
   * @param primaryJarPath Output path for the primary jar file.
   * @param secondaryJarDir Output location for secondary jar files.  Note that this directory may
   *     be empty if no secondary jar files are needed.
   * @param secondaryJarPattern Filename pattern for secondary jar files.  Pattern contains one %d
   *     argument representing the enumerated secondary zip count (starting at 1).
   * @param primaryDexSubstrings Set of substrings that, when matched, will cause individual input
   *     class or resource files to be placed into the primary jar (and thus the primary dex
   *     output).
   */
  public SplitZipStep(
      Set<String> inputPathsToSplit,
      String secondaryJarMetaPath,
      String primaryJarPath,
      String secondaryJarDir,
      String secondaryJarPattern,
      Set<String> primaryDexSubstrings,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      DexStore dexStore) {
    this.inputPathsToSplit = ImmutableSet.copyOf(inputPathsToSplit);
    this.secondaryJarMetaPath = Preconditions.checkNotNull(secondaryJarMetaPath);
    this.primaryJarPath = Preconditions.checkNotNull(primaryJarPath);
    this.secondaryJarDir = Preconditions.checkNotNull(secondaryJarDir);
    this.secondaryJarPattern = Preconditions.checkNotNull(secondaryJarPattern);
    this.primaryDexSubstrings = ImmutableSet.copyOf(primaryDexSubstrings);
    this.dexSplitStrategy = Preconditions.checkNotNull(dexSplitStrategy);
    this.dexStore = Preconditions.checkNotNull(dexStore);

    this.requiredInPrimaryZip = new Predicate<String>() {
      @Override
      public boolean apply(String name) {
        // This is a bit of a hack.  DX automatically strips non-class assets from the primary
        // dex (because the output is classes.dex, which cannot contain assets), but not from
        // secondary dex jars (because the output is a jar that can contain assets), so we put
        // all assets in the primary jar to ensure that they get dropped.
        if (!name.endsWith(".class")) {
          return true;
        }
        for (String substr : SplitZipStep.this.primaryDexSubstrings) {
          if (name.contains(substr)) {
            return true;
          }
        }
        return false;
      }
    };
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      File primaryJarFile = new File(primaryJarPath);
      Collection<File> secondaryZips = ZipSplitter.splitZip(
          ImmutableSet.copyOf(Paths.transformPathToFile(inputPathsToSplit)),
          primaryJarFile,
          new File(secondaryJarDir),
          secondaryJarPattern,
          ZIP_SIZE_SOFT_LIMIT,
          ZIP_SIZE_HARD_LIMIT,
          requiredInPrimaryZip,
          dexSplitStrategy);

      BufferedWriter secondaryMetaInfoWriter = Files.newWriter(new File(secondaryJarMetaPath),
          Charsets.UTF_8);
      try {
        writeMetaList(secondaryMetaInfoWriter, secondaryZips, dexStore);
      } finally {
        secondaryMetaInfoWriter.close();
      }

      return 0;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }

  @VisibleForTesting
  static void writeMetaList(
      BufferedWriter writer,
      Collection<File> jarFiles,
      DexStore dexStore) throws IOException {
    for (File secondary : jarFiles) {
      String filename = SmartDexingStep.transformInputToDexOutput(
           secondary.getName(), dexStore);
      String jarHash = hexSha1(secondary);
      String containedClass = findAnyClass(secondary);
      writer.write(String.format("%s %s %s",
          filename, jarHash, containedClass));
      writer.newLine();
    }
  }

  private static String findAnyClass(File jarFile) throws IOException {
    ZipFile inZip = new ZipFile(jarFile);
    for (ZipEntry entry : Collections.list(inZip.entries())) {
      Matcher m = classFilePattern.matcher(entry.getName());
      if (m.matches()) {
        return m.group(1).replace('/', '.');
      }
    }
    // TODO(user): It's possible for this to happen by chance, so we should handle it better.
    throw new IllegalStateException("Couldn't find any class in " + jarFile.getAbsolutePath());
  }

  private static String hexSha1(File file) throws IOException {
    return Files.hash(file, Hashing.sha1()).toString();
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "split-zip";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(' ').join(
        "split-zip",
        Joiner.on(':').join(inputPathsToSplit),
        secondaryJarMetaPath,
        primaryJarPath,
        secondaryJarDir,
        secondaryJarPattern,
        ZIP_SIZE_HARD_LIMIT
    );
  }
}

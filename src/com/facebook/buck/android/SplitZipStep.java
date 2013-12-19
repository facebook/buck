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

import com.facebook.buck.dalvik.DalvikAwareZipSplitterFactory;
import com.facebook.buck.dalvik.DefaultZipSplitterFactory;
import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.dalvik.ZipSplitterFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

/**
 * Split zipping tool designed to divide input code blobs into a set of output jar files such that
 * none will exceed the DexOpt LinearAlloc limit or the dx method limit when passed through
 * dx --dex.
 */
public class SplitZipStep implements Step {

  private static final int ZIP_SIZE_SOFT_LIMIT = 11 * 1024 * 1024;

  /**
   * The uncompressed class size is a very simple metric that we can use to roughly estimate
   * whether we will hit the DexOpt LinearAlloc limit.  When we hit the limit, we were around
   * 20 MB uncompressed, so use 13 MB as a safer upper limit.
   */
  private static final int ZIP_SIZE_HARD_LIMIT = ZIP_SIZE_SOFT_LIMIT + (2 * 1024 * 1024);

  @VisibleForTesting
  static final Pattern classFilePattern = Pattern.compile("^([\\w/$]+)\\.class");

  private final Set<String> inputPathsToSplit;
  private final String secondaryJarMetaPath;
  private final String primaryJarPath;
  private final String secondaryJarDir;
  private final String secondaryJarPattern;
  private final Optional<Path> proguardMappingFile;
  private final ImmutableSet<String> primaryDexPatterns;
  private final Optional<Path> primaryDexClassesFile;
  private final ZipSplitter.DexSplitStrategy dexSplitStrategy;
  private final DexStore dexStore;
  private final String pathToReportDir;
  private final boolean useLinearAllocSplitDex;
  private final long linearAllocHardLimit;

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
   * @param primaryDexPatterns Set of substrings that, when matched, will cause individual input
   *     class or resource files to be placed into the primary jar (and thus the primary dex
   *     output).
   * @param useLinearAllocSplitDex If true, {@link com.facebook.buck.dalvik.DalvikAwareZipSplitter} will be used. Also,
   *     {@code linearAllocHardLimit} must have a positive value in this case.
   */
  public SplitZipStep(
      Set<String> inputPathsToSplit,
      String secondaryJarMetaPath,
      String primaryJarPath,
      String secondaryJarDir,
      String secondaryJarPattern,
      Optional<Path> proguardMappingFile,
      Set<String> primaryDexPatterns,
      Optional<Path> primaryDexClassesFile,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      DexStore dexStore,
      String pathToReportDir,
      boolean useLinearAllocSplitDex,
      long linearAllocHardLimit) {
    this.inputPathsToSplit = ImmutableSet.copyOf(inputPathsToSplit);
    this.secondaryJarMetaPath = Preconditions.checkNotNull(secondaryJarMetaPath);
    this.primaryJarPath = Preconditions.checkNotNull(primaryJarPath);
    this.secondaryJarDir = Preconditions.checkNotNull(secondaryJarDir);
    this.secondaryJarPattern = Preconditions.checkNotNull(secondaryJarPattern);
    this.proguardMappingFile = Preconditions.checkNotNull(proguardMappingFile);
    this.primaryDexPatterns = ImmutableSet.copyOf(primaryDexPatterns);
    this.primaryDexClassesFile = Preconditions.checkNotNull(primaryDexClassesFile);
    this.dexSplitStrategy = Preconditions.checkNotNull(dexSplitStrategy);
    this.dexStore = Preconditions.checkNotNull(dexStore);
    this.pathToReportDir = Preconditions.checkNotNull(pathToReportDir);
    this.useLinearAllocSplitDex = useLinearAllocSplitDex;
    this.linearAllocHardLimit = linearAllocHardLimit;
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      Predicate<String> requiredInPrimaryZip = createRequiredInPrimaryZipPredicate(context);

      ZipSplitterFactory zipSplitterFactory;
      if (useLinearAllocSplitDex) {
        zipSplitterFactory = new DalvikAwareZipSplitterFactory(linearAllocHardLimit);
      } else {
        zipSplitterFactory = new DefaultZipSplitterFactory(ZIP_SIZE_SOFT_LIMIT,
            ZIP_SIZE_HARD_LIMIT);
      }

      ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
      File primaryJarFile = new File(primaryJarPath);
      Collection<File> secondaryZips = zipSplitterFactory.newInstance(
          FluentIterable.from(inputPathsToSplit)
              .transform(context.getProjectFilesystem().getPathRelativizer())
              .toSet(),
          primaryJarFile,
          new File(secondaryJarDir),
          secondaryJarPattern,
          requiredInPrimaryZip,
          dexSplitStrategy,
          ZipSplitter.CanaryStrategy.INCLUDE_CANARIES,
          projectFilesystem.getFileForRelativePath(pathToReportDir))
          .execute();

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
  Predicate<String> createRequiredInPrimaryZipPredicate(ExecutionContext context)
      throws IOException {
    final Function<String, String> deobfuscate = createProguardDeobfuscator(context);
    final ImmutableSet<String> primaryDexClassNames = getPrimaryDexClassNames(context);

    return new Predicate<String>() {
      @Override
      public boolean apply(String classFileName) {
        // This is a bit of a hack.  DX automatically strips non-class assets from the primary
        // dex (because the output is classes.dex, which cannot contain assets), but not from
        // secondary dex jars (because the output is a jar that can contain assets), so we put
        // all assets in the primary jar to ensure that they get dropped.
        if (!classFileName.endsWith(".class")) {
          return true;
        }

        // Drop the ".class" suffix and deobfuscate the class name before we apply our checks.
        String internalClassName = Preconditions.checkNotNull(
            deobfuscate.apply(classFileName.replaceAll("\\.class$", "")));

        if (primaryDexClassNames.contains(internalClassName)) {
          return true;
        }

        for (String substr : SplitZipStep.this.primaryDexPatterns) {
          if (internalClassName.contains(substr)) {
            return true;
          }
        }
        return false;
      }
    };
  }

  /**
   * Construct a {@link Set} of internal class names that should go into the primary dex.
   * @return the Set.
   */
  private ImmutableSet<String> getPrimaryDexClassNames(ExecutionContext context)
      throws IOException {
    if (!primaryDexClassesFile.isPresent()) {
      return ImmutableSet.of();
    }

    Path manifest = primaryDexClassesFile.get();
    return FluentIterable
        .from(context.getProjectFilesystem().readLines(manifest))
        .transform(new Function<String, String>() {
          @Override
          public String apply(String line) {
            // Blank lines should be ignored.
            return line.trim();
          }
        })
        .filter(new Predicate<String>() {
          @Override
          public boolean apply(String line) {
            // Allow users to use # to comment out a line.
            return !line.isEmpty() && !(line.charAt(0) == '#');
          }
        })
        .toSet();
  }

  /**
   * Create a {@link Function} that will deobfuscate internal class names for this build.
   * @return the Function.
   */
  private Function<String, String> createProguardDeobfuscator(ExecutionContext context)
      throws IOException {
    if (!proguardMappingFile.isPresent()) {
      return Functions.identity();
    }

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    Path pathToProguardMappingFile = proguardMappingFile.get();
    // Proguard doesn't print a mapping when obfuscation is disabled.
    // TODO(user): Make sure obfuscation was disabled.
    if (!projectFilesystem.exists(pathToProguardMappingFile.toString())) {
      return Functions.identity();
    }

    List<String> lines = projectFilesystem.readLines(pathToProguardMappingFile);
    Map<String, String> rawProguardMap = ProguardMapping.readClassMapping(lines);

    ImmutableMap.Builder<String, String> internalNameBuilder = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : rawProguardMap.entrySet()) {
      internalNameBuilder.put(
          entry.getValue().replace('.', '/'),
          entry.getKey().replace('.', '/'));
    }
    final Map<String, String> deobfuscator = internalNameBuilder.build();

    return new Function<String, String>() {
      @Nullable
      @Override
      public String apply(@Nullable String input) {
        return deobfuscator.get(input);
      }
    };

  }

  @VisibleForTesting
  static void writeMetaList(
      BufferedWriter writer,
      Collection<File> jarFiles,
      DexStore dexStore) throws IOException {
    for (File secondary : jarFiles) {
      String filename = SmartDexingStep.transformInputToDexOutput(secondary, dexStore);
      String jarHash = hexSha1(secondary);
      String containedClass = findAnyClass(secondary);
      writer.write(String.format("%s %s %s",
          filename, jarHash, containedClass));
      writer.newLine();
    }
  }

  private static String findAnyClass(File jarFile) throws IOException {
    try (ZipFile inZip = new ZipFile(jarFile)) {
      for (ZipEntry entry : Collections.list(inZip.entries())) {
        Matcher m = classFilePattern.matcher(entry.getName());
        if (m.matches()) {
          return m.group(1).replace('/', '.');
        }
      }
    }
    // TODO(user): It's possible for this to happen by chance, so we should handle it better.
    throw new IllegalStateException("Couldn't find any class in " + jarFile.getAbsolutePath());
  }

  private static String hexSha1(File file) throws IOException {
    return Files.hash(file, Hashing.sha1()).toString();
  }

  @Override
  public String getShortName() {
    return "split_zip";
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

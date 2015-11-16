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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;
import com.google.common.io.Resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/**
 * Build a fat JAR that packages an inner JAR along with any required native libraries.
 */
public class JarFattener extends AbstractBuildRule implements BinaryBuildRule {

  private static final String FAT_JAR_INNER_JAR = "inner.jar";
  private static final String FAT_JAR_NATIVE_LIBRARY_RESOURCE_ROOT = "nativelibs";
  public static final ImmutableList<String> FAT_JAR_SRC_RESOURCES =
      ImmutableList.of(
          "com/facebook/buck/jvm/java/FatJar.java",
          "com/facebook/buck/util/exportedfiles/Nullable.java",
          "com/facebook/buck/util/exportedfiles/Preconditions.java"
      );
  public static final String FAT_JAR_MAIN_SRC_RESOURCE =
      "com/facebook/buck/jvm/java/FatJarMain.java";

  private final JavacOptions javacOptions;
  @AddToRuleKey
  private final SourcePath innerJar;
  @AddToRuleKey
  private final ImmutableMap<String, SourcePath> nativeLibraries;
  private final Path output;

  public JarFattener(
      BuildRuleParams params,
      SourcePathResolver resolver,
      JavacOptions javacOptions,
      SourcePath innerJar,
      ImmutableMap<String, SourcePath> nativeLibraries) {
    super(params, resolver);
    this.javacOptions = javacOptions;
    this.innerJar = innerJar;
    this.nativeLibraries = nativeLibraries;
    this.output = BuildTargets.getScratchPath(getBuildTarget(), "%s")
        .resolve(getBuildTarget().getShortName() + ".jar");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path outputDir = getOutputDirectory();
    Path fatJarDir = outputDir.resolve("fat-jar-directory");
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDir));

    // Map of the system-specific shared library name to it's resource name as a string.
    ImmutableMap.Builder<String, String> sonameToResourceMapBuilder = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> entry : nativeLibraries.entrySet()) {
      String resource = FAT_JAR_NATIVE_LIBRARY_RESOURCE_ROOT + "/" + entry.getKey();
      sonameToResourceMapBuilder.put(entry.getKey(), resource);
      steps.add(new MkdirStep(getProjectFilesystem(), fatJarDir.resolve(resource).getParent()));
      steps.add(
          new SymlinkFileStep(
              getProjectFilesystem(),
              getResolver().deprecatedGetPath(entry.getValue()),
              fatJarDir.resolve(resource),
              /* useAbsolutePaths */ false));
    }
    ImmutableMap<String, String> sonameToResourceMap = sonameToResourceMapBuilder.build();

    // Grab the source path representing the fat jar info resource.
    Path fatJarInfo = fatJarDir.resolve(FatJar.FAT_JAR_INFO_RESOURCE);
    steps.add(writeFatJarInfo(fatJarInfo, sonameToResourceMap));

    // Build up the resource and src collections.
    Set<Path> javaSourceFilePaths = Sets.newHashSet();
    for (String srcResource : FAT_JAR_SRC_RESOURCES) {
      Path fatJarSource = outputDir.resolve(Paths.get(srcResource).getFileName());
      javaSourceFilePaths.add(fatJarSource);
      steps.add(writeFromResource(fatJarSource, srcResource));
    }
    Path fatJarMainSource = outputDir.resolve(Paths.get(FAT_JAR_MAIN_SRC_RESOURCE).getFileName());
    javaSourceFilePaths.add(fatJarMainSource);
    steps.add(writeFromResource(fatJarMainSource, FAT_JAR_MAIN_SRC_RESOURCE));

    // Symlink the inner JAR into it's place in the fat JAR.
    steps.add(
        new MkdirStep(
            getProjectFilesystem(),
            fatJarDir.resolve(FAT_JAR_INNER_JAR).getParent()));
    steps.add(
        new SymlinkFileStep(
            getProjectFilesystem(),
            getResolver().deprecatedGetPath(innerJar),
            fatJarDir.resolve(FAT_JAR_INNER_JAR),
            /* useAbsolutePaths */ false));

    // Build the final fat JAR from the structure we've layed out above.  We first package the
    // fat jar resources (e.g. native libs) using the "stored" compression level, to avoid
    // expensive compression on builds and decompression on startup.
    Path zipped = outputDir.resolve("contents.zip");

    Step zipStep = new ZipStep(
        getProjectFilesystem(),
        zipped,
        ImmutableSet.<Path>of(),
            /* junkPaths */ false,
            /* compressionLevel */ 0,
        fatJarDir);

    JavacStepFactory javacStepFactory = new JavacStepFactory(javacOptions);

    steps.add(javacStepFactory.createCompileStep(
        ImmutableSortedSet.copyOf(javaSourceFilePaths),
        getBuildTarget(),
        getResolver(),
        getProjectFilesystem(),
        /* classpathEntries */ ImmutableSortedSet.<Path>of(),
        fatJarDir,
        /* workingDir */ Optional.<Path>absent(),
        /* pathToSrcsList */ Optional.<Path>absent(),
        /* suggestBuildRule */ Optional.<JavacStep.SuggestBuildRules>absent()));
    steps.add(zipStep);
    steps.add(
        new JarDirectoryStep(
            getProjectFilesystem(),
            getOutputPath(),
            ImmutableSortedSet.of(zipped),
            FatJarMain.class.getName(),
            /* manifestFile */ null));


    return steps.build();
  }

  /**
   * @return a {@link Step} that generates the fat jar info resource.
   */
  private Step writeFatJarInfo(
      Path destination,
      final ImmutableMap<String, String> nativeLibraries) {

    ByteSource source = new ByteSource() {
      @Override
      public InputStream openStream() throws IOException {
        FatJar fatJar = new FatJar(FAT_JAR_INNER_JAR, nativeLibraries);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
          fatJar.store(bytes);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
        return new ByteArrayInputStream(bytes.toByteArray());
      }
    };

    return new WriteFileStep(getProjectFilesystem(), source, destination, /* executable */ false);
  }

  /**
   * @return a {@link Step} that writes the final from the resource named {@code name}.
   */
  private Step writeFromResource(Path destination, final String name) {
    return new WriteFileStep(
        getProjectFilesystem(),
        Resources.asByteSource(Resources.getResource(name)),
        destination,
        /* executable */ false);
  }

  private Path getOutputDirectory() {
    return output.getParent();
  }

  private Path getOutputPath() {
    return output;
  }

  @Override
  public Path getPathToOutput() {
    return getOutputPath();
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder()
        .addArg("java")
        .addArg("-jar")
        .addArg(new BuildTargetSourcePath(getBuildTarget()))
        .build();
  }

}

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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.zip.ZipStep;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.io.Resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Build a fat JAR that packages an inner JAR along with any required native libraries.
 */
public class JarFattener extends AbstractBuildRule implements BinaryBuildRule {

  private static final String FAT_JAR_INNER_JAR = "inner.jar";
  private static final String FAT_JAR_NATIVE_LIBRARY_RESOURCE_ROOT = "nativelibs";
  public static final String FAT_JAR_SRC_RESOURCE = "com/facebook/buck/java/FatJar.java";
  public static final String FAT_JAR_MAIN_SRC_RESOURCE = "com/facebook/buck/java/FatJarMain.java";

  private final JavacOptions javacOptions;
  private final SourcePath innerJar;
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
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(ImmutableList.of(innerJar));
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    for (Map.Entry<String, SourcePath> entry : nativeLibraries.entrySet()) {
      builder.setReflectively("nativelib:" + entry.getKey(), entry.getValue());
    }
    return builder;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path outputDir = getOutputDirectory();
    Path fatJarDir = outputDir.resolve("fat-jar-directory");
    steps.add(new MakeCleanDirectoryStep(outputDir));

    // Map of the system-specific shared library name to it's resource name as a string.
    ImmutableMap.Builder<String, String> sonameToResourceMapBuilder = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> entry : nativeLibraries.entrySet()) {
      String resource = FAT_JAR_NATIVE_LIBRARY_RESOURCE_ROOT + "/" + entry.getKey();
      sonameToResourceMapBuilder.put(entry.getKey(), resource);
      steps.add(new MkdirStep(fatJarDir.resolve(resource).getParent()));
      steps.add(
          new SymlinkFileStep(
              getResolver().getPath(entry.getValue()),
              fatJarDir.resolve(resource),
              /* useAbsolutePaths */ false));
    }
    ImmutableMap<String, String> sonameToResourceMap = sonameToResourceMapBuilder.build();

    // Grab the source path representing the fat jar info resource.
    Path fatJarInfo = fatJarDir.resolve(FatJar.FAT_JAR_INFO_RESOURCE);
    steps.add(writeFatJarInfo(fatJarInfo, sonameToResourceMap));

    // Build up the resource and src collections.
    Path fatJarSource = outputDir.resolve(Paths.get(FAT_JAR_SRC_RESOURCE).getFileName());
    steps.add(writeFromResource(fatJarSource, FAT_JAR_SRC_RESOURCE));
    Path fatJarMainSource = outputDir.resolve(Paths.get(FAT_JAR_MAIN_SRC_RESOURCE).getFileName());
    steps.add(writeFromResource(fatJarMainSource, FAT_JAR_MAIN_SRC_RESOURCE));

    steps.add(
        new JavacStep(
            fatJarDir,
            Optional.<Path>absent(),
            ImmutableSet.of(fatJarSource, fatJarMainSource),
            Optional.<Path>absent(),
            /* transitive classpath */ ImmutableSet.<Path>of(),
            /* declared classpath */ ImmutableSet.<Path>of(),
            javacOptions,
            getBuildTarget(),
            BuildDependencies.FIRST_ORDER_ONLY,
            Optional.<JavacStep.SuggestBuildRules>absent()));

    // Symlink the inner JAR into it's place in the fat JAR.
    steps.add(new MkdirStep(fatJarDir.resolve(FAT_JAR_INNER_JAR).getParent()));
    steps.add(
        new SymlinkFileStep(
            getResolver().getPath(innerJar),
            fatJarDir.resolve(FAT_JAR_INNER_JAR),
            /* useAbsolutePaths */ false));

    // Build the final fat JAR from the structure we've layed out above.  We first package the
    // fat jar resources (e.g. native libs) using the "stored" compression level, to avoid
    // expensive compression on builds and decompression on startup.
    Path zipped = outputDir.resolve("contents.zip");
    steps.add(
        new ZipStep(
            zipped,
            ImmutableSet.<Path>of(),
            /* junkPaths */ false,
            /* compressionLevel */ 0,
            fatJarDir));
    steps.add(
        new JarDirectoryStep(
            getOutputPath(),
            ImmutableSet.of(zipped),
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

    return new WriteFileStep(source, destination);
  }

  /**
   * @return a {@link Step} that writes the final from the resource named {@code name}.
   */
  private Step writeFromResource(Path destination, final String name) {
    return new WriteFileStep(Resources.asByteSource(Resources.getResource(name)), destination);
  }

  private Path getOutputDirectory() {
    return output.getParent();
  }

  private Path getOutputPath() {
    return output;
  }

  @Override
  public Path getPathToOutputFile() {
    return getOutputPath();
  }

  @Override
  public ImmutableList<String> getExecutableCommand(ProjectFilesystem projectFilesystem) {
    return ImmutableList.of("java", "-jar", projectFilesystem.resolve(getOutputPath()).toString());
  }

}

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

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.io.ByteSource;
import com.google.common.io.Resources;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import javax.xml.bind.JAXBException;

/** Build a fat JAR that packages an inner JAR along with any required native libraries. */
public class JarFattener extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule {

  private static final String FAT_JAR_INNER_JAR = "inner.jar";
  private static final String FAT_JAR_NATIVE_LIBRARY_RESOURCE_ROOT = "nativelibs";
  public static final ImmutableList<String> FAT_JAR_SRC_RESOURCES =
      ImmutableList.of(
          "com/facebook/buck/jvm/java/FatJar.java",
          "com/facebook/buck/util/liteinfersupport/Nullable.java",
          "com/facebook/buck/util/liteinfersupport/Preconditions.java");
  public static final String FAT_JAR_MAIN_SRC_RESOURCE =
      "com/facebook/buck/jvm/java/FatJarMain.java";

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private final JavacOptions javacOptions;
  @AddToRuleKey private final SourcePath innerJar;
  @AddToRuleKey private final ImmutableMap<String, SourcePath> nativeLibraries;
  // We're just propagating the runtime launcher through `getExecutiable`, so don't add it to the
  // rule key.
  private final Tool javaRuntimeLauncher;
  private final Path output;

  public JarFattener(
      BuildTarget buildTarget,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Javac javac,
      JavacOptions javacOptions,
      SourcePath innerJar,
      ImmutableMap<String, SourcePath> nativeLibraries,
      Tool javaRuntimeLauncher) {
    super(buildTarget, projectFilesystem, params);
    this.ruleFinder = ruleFinder;
    this.javac = javac;
    this.javacOptions = javacOptions;
    this.innerJar = innerJar;
    this.nativeLibraries = nativeLibraries;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.output =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
            .resolve(getBuildTarget().getShortName() + ".jar");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path outputDir = getOutputDirectory();
    Path fatJarDir = outputDir.resolve("fat-jar-directory");
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputDir)));

    // Map of the system-specific shared library name to it's resource name as a string.
    ImmutableMap.Builder<String, String> sonameToResourceMapBuilder = ImmutableMap.builder();
    for (Map.Entry<String, SourcePath> entry : nativeLibraries.entrySet()) {
      String resource = FAT_JAR_NATIVE_LIBRARY_RESOURCE_ROOT + "/" + entry.getKey();
      sonameToResourceMapBuilder.put(entry.getKey(), resource);
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  fatJarDir.resolve(resource).getParent())));
      steps.add(
          SymlinkFileStep.builder()
              .setFilesystem(getProjectFilesystem())
              .setExistingFile(context.getSourcePathResolver().getAbsolutePath(entry.getValue()))
              .setDesiredLink(fatJarDir.resolve(resource))
              .build());
    }
    ImmutableMap<String, String> sonameToResourceMap = sonameToResourceMapBuilder.build();

    // Grab the source path representing the fat jar info resource.
    Path fatJarInfo = fatJarDir.resolve(FatJar.FAT_JAR_INFO_RESOURCE);
    steps.add(writeFatJarInfo(fatJarInfo, sonameToResourceMap));

    // Build up the resource and src collections.
    ImmutableSortedSet.Builder<Path> javaSourceFilePaths =
        new ImmutableSortedSet.Builder<>(Ordering.natural());
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
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                fatJarDir.resolve(FAT_JAR_INNER_JAR).getParent())));
    steps.add(
        SymlinkFileStep.builder()
            .setFilesystem(getProjectFilesystem())
            .setExistingFile(context.getSourcePathResolver().getAbsolutePath(innerJar))
            .setDesiredLink(fatJarDir.resolve(FAT_JAR_INNER_JAR))
            .build());

    // Build the final fat JAR from the structure we've layed out above.  We first package the
    // fat jar resources (e.g. native libs) using the "stored" compression level, to avoid
    // expensive compression on builds and decompression on startup.
    Path zipped = outputDir.resolve("contents.zip");

    Step zipStep =
        new ZipStep(
            getProjectFilesystem(),
            zipped,
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.NONE,
            fatJarDir);

    CompilerParameters compilerParameters =
        CompilerParameters.builder()
            .setClasspathEntries(ImmutableSortedSet.of())
            .setSourceFilePaths(javaSourceFilePaths.build())
            .setScratchPaths(getBuildTarget(), getProjectFilesystem())
            .setOutputDirectory(fatJarDir)
            .build();

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                compilerParameters.getPathToSourcesList().getParent())));

    JavacToJarStepFactory compileStepFactory =
        new JavacToJarStepFactory(
            context.getSourcePathResolver(),
            ruleFinder,
            getProjectFilesystem(),
            javac,
            javacOptions,
            ExtraClasspathProvider.EMPTY);

    compileStepFactory.createCompileStep(
        context, getBuildTarget(), compilerParameters, steps, buildableContext);

    steps.add(zipStep);
    JarParameters jarParameters =
        JarParameters.builder()
            .setJarPath(output)
            .setEntriesToJar(ImmutableSortedSet.of(zipped))
            .setMainClass(Optional.of(FatJarMain.class.getName()))
            .setMergeManifests(true)
            .build();
    steps.add(new JarDirectoryStep(getProjectFilesystem(), jarParameters));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  /** @return a {@link Step} that generates the fat jar info resource. */
  private Step writeFatJarInfo(
      Path destination, final ImmutableMap<String, String> nativeLibraries) {

    ByteSource source =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            FatJar fatJar = new FatJar(FAT_JAR_INNER_JAR, nativeLibraries);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
              fatJar.store(bytes);
            } catch (JAXBException e) {
              throw new RuntimeException(e);
            }
            return new ByteArrayInputStream(bytes.toByteArray());
          }
        };

    return new WriteFileStep(getProjectFilesystem(), source, destination, /* executable */ false);
  }

  /** @return a {@link Step} that writes the final from the resource named {@code name}. */
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

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder(javaRuntimeLauncher)
        .addArg("-jar")
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .build();
  }

  public ImmutableMap<String, SourcePath> getNativeLibraries() {
    return nativeLibraries;
  }
}

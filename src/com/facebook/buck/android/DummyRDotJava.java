/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JarDirectoryStep;
import com.facebook.buck.jvm.java.NoOpClassUsageFileWriter;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Buildable that takes in a list of {@link HasAndroidResourceDeps} and for each of these rules,
 * first creates an {@code R.java} file using {@link MergeAndroidResourcesStep} and compiles it to
 * generate a corresponding {@code R.class} file. These are called "dummy" {@code R.java} files
 * since these are later merged together into a single {@code R.java} file by {@link AaptStep}.
 */
public class DummyRDotJava extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, InitializableFromDisk<Object>, HasJavaAbi {

  private final ImmutableList<HasAndroidResourceDeps> androidResourceDeps;
  private final Path outputJar;
  private final JarContentsSupplier outputJarContentsSupplier;
  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey CompileToJarStepFactory compileStepFactory;
  @AddToRuleKey private final boolean forceFinalResourceIds;
  @AddToRuleKey private final Optional<String> unionPackage;
  @AddToRuleKey private final Optional<String> finalRName;
  @AddToRuleKey private final boolean useOldStyleableFormat;

  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final ImmutableList<SourcePath> abiInputs;

  public DummyRDotJava(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      Set<HasAndroidResourceDeps> androidResourceDeps,
      CompileToJarStepFactory compileStepFactory,
      boolean forceFinalResourceIds,
      Optional<String> unionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat) {
    this(
        params,
        ruleFinder,
        androidResourceDeps,
        compileStepFactory,
        forceFinalResourceIds,
        unionPackage,
        finalRName,
        useOldStyleableFormat,
        abiPaths(androidResourceDeps));
  }

  private DummyRDotJava(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      Set<HasAndroidResourceDeps> androidResourceDeps,
      CompileToJarStepFactory compileStepFactory,
      boolean forceFinalResourceIds,
      Optional<String> unionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat,
      ImmutableList<SourcePath> abiInputs) {
    super(params.copyAppendingExtraDeps(() -> ruleFinder.filterBuildRuleInputs(abiInputs)));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    this.ruleFinder = ruleFinder;
    // Sort the input so that we get a stable ABI for the same set of resources.
    this.androidResourceDeps =
        androidResourceDeps
            .stream()
            .sorted(Comparator.comparing(HasAndroidResourceDeps::getBuildTarget))
            .collect(MoreCollectors.toImmutableList());
    this.useOldStyleableFormat = useOldStyleableFormat;
    this.outputJar = getOutputJarPath(getBuildTarget(), getProjectFilesystem());
    this.compileStepFactory = compileStepFactory;
    this.forceFinalResourceIds = forceFinalResourceIds;
    this.unionPackage = unionPackage;
    this.finalRName = finalRName;
    this.abiInputs = abiInputs;
    this.outputJarContentsSupplier = new JarContentsSupplier(resolver, getSourcePathToOutput());
  }

  private static ImmutableList<SourcePath> abiPaths(Iterable<HasAndroidResourceDeps> deps) {
    FluentIterable<HasAndroidResourceDeps> iter = FluentIterable.from(deps);
    return iter.transform(HasAndroidResourceDeps::getPathToTextSymbolsFile)
        .append(iter.transform(HasAndroidResourceDeps::getPathToRDotJavaPackageFile))
        .toList();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    final Path rDotJavaSrcFolder = getRDotJavaSrcFolder(getBuildTarget(), getProjectFilesystem());
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), rDotJavaSrcFolder));

    // Generate the .java files and record where they will be written in javaSourceFilePaths.
    ImmutableSortedSet<Path> javaSourceFilePaths;
    if (androidResourceDeps.isEmpty()) {
      // In this case, the user is likely running a Robolectric test that does not happen to
      // depend on any resources. However, if Robolectric doesn't find an R.java file, it flips
      // out, so we have to create one, anyway.

      // TODO(mbolin): Stop hardcoding com.facebook. This should match the package in the
      // associated TestAndroidManifest.xml file.
      Path emptyRDotJava = rDotJavaSrcFolder.resolve("com/facebook/R.java");
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), emptyRDotJava.getParent()));
      steps.add(
          new WriteFileStep(
              getProjectFilesystem(),
              "package com.facebook;\n public class R {}\n",
              emptyRDotJava,
              /* executable */ false));
      javaSourceFilePaths = ImmutableSortedSet.of(emptyRDotJava);
    } else {
      MergeAndroidResourcesStep mergeStep =
          MergeAndroidResourcesStep.createStepForDummyRDotJava(
              getProjectFilesystem(),
              context.getSourcePathResolver(),
              androidResourceDeps,
              rDotJavaSrcFolder,
              forceFinalResourceIds,
              unionPackage,
              /* rName */ Optional.empty(),
              useOldStyleableFormat);
      steps.add(mergeStep);

      if (!finalRName.isPresent()) {
        javaSourceFilePaths = mergeStep.getRDotJavaFiles();
      } else {
        MergeAndroidResourcesStep mergeFinalRStep =
            MergeAndroidResourcesStep.createStepForDummyRDotJava(
                getProjectFilesystem(),
                context.getSourcePathResolver(),
                androidResourceDeps,
                rDotJavaSrcFolder,
                /* forceFinalResourceIds */ true,
                unionPackage,
                finalRName,
                useOldStyleableFormat);
        steps.add(mergeFinalRStep);

        javaSourceFilePaths =
            ImmutableSortedSet.<Path>naturalOrder()
                .addAll(mergeStep.getRDotJavaFiles())
                .addAll(mergeFinalRStep.getRDotJavaFiles())
                .build();
      }
    }

    // Clear out the directory where the .class files will be generated.
    final Path rDotJavaClassesFolder = getRDotJavaBinFolder();
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), rDotJavaClassesFolder));

    Path pathToJarOutputDir = outputJar.getParent();
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToJarOutputDir));

    Path pathToSrcsList =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "__%s__srcs");
    steps.add(MkdirStep.of(getProjectFilesystem(), pathToSrcsList.getParent()));

    // Compile the .java files.
    compileStepFactory.createCompileStep(
        context,
        javaSourceFilePaths,
        getBuildTarget(),
        context.getSourcePathResolver(),
        ruleFinder,
        getProjectFilesystem(),
        /* declared classpath */ ImmutableSortedSet.of(),
        rDotJavaClassesFolder,
        Optional.empty(),
        pathToSrcsList,
        NoOpClassUsageFileWriter.instance(),
        steps,
        buildableContext);
    buildableContext.recordArtifact(rDotJavaClassesFolder);

    steps.add(
        new JarDirectoryStep(
            getProjectFilesystem(),
            outputJar,
            ImmutableSortedSet.of(rDotJavaClassesFolder),
            /* mainClass */ null,
            /* manifestFile */ null,
            /* mergeManifests */ true,
            /* hashEntries */ true,
            /* blacklist */ ImmutableSet.of()));
    buildableContext.recordArtifact(outputJar);

    steps.add(new CheckDummyRJarNotEmptyStep(javaSourceFilePaths));

    return steps.build();
  }

  @Override
  public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    outputJarContentsSupplier.load();
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  private class CheckDummyRJarNotEmptyStep implements Step {
    private final ImmutableSortedSet<Path> javaSourceFilePaths;

    CheckDummyRJarNotEmptyStep(ImmutableSortedSet<Path> javaSourceFilePaths) {
      this.javaSourceFilePaths = javaSourceFilePaths;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      try (ZipFile jar = new ZipFile(getProjectFilesystem().resolve(outputJar).toFile())) {
        for (ZipEntry zipEntry : Collections.list(jar.entries())) {
          if (zipEntry.getName().endsWith(".class")) {
            // We found a class, so the jar is probably fine.
            return StepExecutionResult.SUCCESS;
          }
        }
      }

      StringBuilder sb = new StringBuilder();
      for (Path file : javaSourceFilePaths) {
        BasicFileAttributes attrs =
            getProjectFilesystem().readAttributes(file, BasicFileAttributes.class);
        sb.append(file);
        sb.append(' ');
        sb.append(attrs.size());
        sb.append('\n');
      }

      throw new RuntimeException(
          String.format(
              "Dummy R.java JAR %s has no classes.  Possible corrupt output.  Is disk full?  "
                  + "Source files:\n%s",
              outputJar, sb));
    }

    @Override
    public String getShortName() {
      return "check_dummy_r_jar_not_empty";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return "check_dummy_r_jar_not_empty " + outputJar;
    }
  }

  public static Path getRDotJavaSrcFolder(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  public static Path getRDotJavaBinFolder(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_bin__");
  }

  private static Path getPathToOutputDir(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "__%s_dummyrdotjava_output__");
  }

  private static Path getOutputJarPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return getPathToOutputDir(buildTarget, filesystem)
        .resolve(String.format("%s.jar", buildTarget.getShortNameAndFlavorPostfix()));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputJar);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return outputJarContentsSupplier.get();
  }

  @Override
  public Optional<BuildTarget> getAbiJar() {
    return Optional.of(getBuildTarget());
  }

  public Path getRDotJavaBinFolder() {
    return getRDotJavaBinFolder(getBuildTarget(), getProjectFilesystem());
  }

  public ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps() {
    return androidResourceDeps;
  }

  @VisibleForTesting
  CompileToJarStepFactory getCompileStepFactory() {
    return compileStepFactory;
  }
}

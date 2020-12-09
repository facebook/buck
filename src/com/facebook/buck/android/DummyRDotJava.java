/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.cell.impl.CellPathResolverUtils;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.CompilerOutputPathsValue;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.FilesystemParams;
import com.facebook.buck.jvm.java.JarParameters;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.JarDirectoryStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
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
  private final RelPath outputJar;
  private final BuildOutputInitializer<Object> buildOutputInitializer;
  private final ImmutableSortedSet<BuildRule> buildDeps;
  @AddToRuleKey private final JavacToJarStepFactory compileStepFactory;
  @AddToRuleKey private final Javac javac;
  @AddToRuleKey private final boolean forceFinalResourceIds;
  @AddToRuleKey private final Optional<String> unionPackage;
  @AddToRuleKey private final Optional<String> finalRName;
  @AddToRuleKey private final boolean useOldStyleableFormat;
  @AddToRuleKey private final boolean skipNonUnionRDotJava;

  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final ImmutableList<SourcePath> abiInputs;

  private final JavaAbiInfo javaAbiInfo;

  public DummyRDotJava(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Set<HasAndroidResourceDeps> androidResourceDeps,
      JavacToJarStepFactory compileStepFactory,
      Javac javac,
      boolean forceFinalResourceIds,
      Optional<String> unionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat,
      boolean skipNonUnionRDotJava) {
    this(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        androidResourceDeps,
        compileStepFactory,
        javac,
        forceFinalResourceIds,
        unionPackage,
        finalRName,
        useOldStyleableFormat,
        abiPaths(androidResourceDeps),
        skipNonUnionRDotJava);
  }

  private DummyRDotJava(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Set<HasAndroidResourceDeps> androidResourceDeps,
      JavacToJarStepFactory compileStepFactory,
      Javac javac,
      boolean forceFinalResourceIds,
      Optional<String> unionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat,
      ImmutableList<SourcePath> abiInputs,
      boolean skipNonUnionRDotJava) {
    super(buildTarget, projectFilesystem);

    // Sort the input so that we get a stable ABI for the same set of resources.
    this.androidResourceDeps =
        androidResourceDeps.stream()
            .sorted(Comparator.comparing(HasAndroidResourceDeps::getBuildTarget))
            .collect(ImmutableList.toImmutableList());
    this.useOldStyleableFormat = useOldStyleableFormat;
    this.skipNonUnionRDotJava = skipNonUnionRDotJava;
    this.outputJar = getOutputJarPath(getBuildTarget(), getProjectFilesystem());
    this.compileStepFactory = compileStepFactory;
    this.javac = javac;
    this.forceFinalResourceIds = forceFinalResourceIds;
    this.unionPackage = unionPackage;
    this.finalRName = finalRName;
    this.abiInputs = abiInputs;
    this.javaAbiInfo = DefaultJavaAbiInfo.of(getSourcePathToOutput());
    buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);

    buildDeps =
        BuildableSupport.deriveDeps(this, ruleFinder)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }

  private static ImmutableList<SourcePath> abiPaths(Iterable<HasAndroidResourceDeps> deps) {
    FluentIterable<HasAndroidResourceDeps> iter = FluentIterable.from(deps);
    return iter.transform(HasAndroidResourceDeps::getPathToTextSymbolsFile)
        .append(iter.transform(HasAndroidResourceDeps::getPathToRDotJavaPackageFile))
        .toList();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    Path buildCellRootPath = context.getBuildCellRootPath();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ProjectFilesystem filesystem = getProjectFilesystem();
    BuildTarget buildTarget = getBuildTarget();
    RelPath rDotJavaSrcFolder = getRDotJavaSrcFolder(buildTarget, filesystem);

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath, filesystem, rDotJavaSrcFolder)));

    // Generate the .java files and record where they will be written in javaSourceFilePaths.
    ImmutableSortedSet<Path> javaSourceFilePaths;
    if (androidResourceDeps.isEmpty()) {
      // In this case, the user is likely running a Robolectric test that does not happen to
      // depend on any resources. However, if Robolectric doesn't find an R.java file, it flips
      // out, so we have to create one, anyway.

      // TODO(mbolin): Stop hardcoding com.facebook. This should match the package in the
      // associated TestAndroidManifest.xml file.
      Path emptyRDotJava = rDotJavaSrcFolder.resolve("com/facebook/R.java");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildCellRootPath, filesystem, emptyRDotJava.getParent())));
      steps.add(
          WriteFileStep.of(
              filesystem.getRootPath(),
              "package com.facebook;\n public class R {}\n",
              emptyRDotJava,
              /* executable */ false));
      javaSourceFilePaths = ImmutableSortedSet.of(emptyRDotJava);
    } else {
      MergeAndroidResourcesStep mergeStep =
          MergeAndroidResourcesStep.createStepForDummyRDotJava(
              filesystem,
              sourcePathResolver,
              androidResourceDeps,
              rDotJavaSrcFolder.getPath(),
              forceFinalResourceIds,
              unionPackage,
              /* rName */ Optional.empty(),
              useOldStyleableFormat,
              skipNonUnionRDotJava);
      steps.add(mergeStep);

      if (!finalRName.isPresent()) {
        javaSourceFilePaths = mergeStep.getRDotJavaFiles();
      } else {
        MergeAndroidResourcesStep mergeFinalRStep =
            MergeAndroidResourcesStep.createStepForDummyRDotJava(
                filesystem,
                sourcePathResolver,
                androidResourceDeps,
                rDotJavaSrcFolder.getPath(),
                /* forceFinalResourceIds */ true,
                unionPackage,
                finalRName,
                useOldStyleableFormat,
                skipNonUnionRDotJava);
        steps.add(mergeFinalRStep);

        javaSourceFilePaths =
            ImmutableSortedSet.<Path>naturalOrder()
                .addAll(mergeStep.getRDotJavaFiles())
                .addAll(mergeFinalRStep.getRDotJavaFiles())
                .build();
      }
    }

    // Clear out the directory where the .class files will be generated.
    RelPath rDotJavaClassesFolder = getRDotJavaBinFolder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath, filesystem, rDotJavaClassesFolder)));

    RelPath pathToJarOutputDir = outputJar.getParent();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath, filesystem, pathToJarOutputDir)));

    BuckPaths buckPaths = filesystem.getBuckPaths();
    BuildTargetValue buildTargetValue = BuildTargetValue.of(buildTarget);
    CompilerParameters compilerParameters =
        CompilerParameters.builder()
            .setClasspathEntries(ImmutableSortedSet.of())
            .setSourceFilePaths(javaSourceFilePaths)
            .setOutputPaths(CompilerOutputPaths.of(buildTarget, buckPaths))
            .build();

    Preconditions.checkState(
        compilerParameters.getOutputPaths().getClassesDir().equals(rDotJavaClassesFolder));

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildCellRootPath,
                filesystem,
                compilerParameters.getOutputPaths().getPathToSourcesList().getParent())));

    AbsPath rootPath = filesystem.getRootPath();

    // Compile the .java files.
    ImmutableList.Builder<IsolatedStep> isolatedSteps = ImmutableList.builder();
    compileStepFactory.createCompileStep(
        FilesystemParams.of(filesystem),
        CellPathResolverUtils.getCellToPathMappings(rootPath, context.getCellPathResolver()),
        buildTargetValue,
        CompilerOutputPathsValue.of(buckPaths, buildTarget),
        compilerParameters,
        isolatedSteps,
        buildableContext,
        javac.resolve(sourcePathResolver),
        compileStepFactory.createExtraParams(context.getSourcePathResolver(), rootPath));
    steps.addAll(isolatedSteps.build());
    buildableContext.recordArtifact(rDotJavaClassesFolder.getPath());

    JarParameters jarParameters =
        JarParameters.builder()
            .setJarPath(outputJar)
            .setEntriesToJar(
                ImmutableSortedSet.orderedBy(RelPath.comparator())
                    .add(rDotJavaClassesFolder)
                    .build())
            .setMergeManifests(false)
            .setHashEntries(true)
            .build();
    steps.add(new JarDirectoryStep(jarParameters));
    buildableContext.recordArtifact(outputJar.getPath());

    steps.add(new CheckDummyRJarNotEmptyStep(javaSourceFilePaths));

    return steps.build();
  }

  @Override
  public void invalidateInitializeFromDiskState() {
    javaAbiInfo.invalidate();
  }

  @Override
  public Object initializeFromDisk(SourcePathResolverAdapter pathResolver) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    javaAbiInfo.load(pathResolver);
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  private class CheckDummyRJarNotEmptyStep implements Step {

    private final ImmutableSortedSet<Path> javaSourceFilePaths;

    CheckDummyRJarNotEmptyStep(ImmutableSortedSet<Path> javaSourceFilePaths) {
      this.javaSourceFilePaths = javaSourceFilePaths;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext context) throws IOException {
      try (ZipFile jar = new ZipFile(getProjectFilesystem().resolve(outputJar).toFile())) {
        for (ZipEntry zipEntry : Collections.list(jar.entries())) {
          if (zipEntry.getName().endsWith(".class")) {
            // We found a class, so the jar is probably fine.
            return StepExecutionResults.SUCCESS;
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
    public String getDescription(StepExecutionContext context) {
      return "check_dummy_r_jar_not_empty " + outputJar;
    }
  }

  public static RelPath getRDotJavaSrcFolder(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  public static RelPath getRDotJavaBinFolder(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return CompilerOutputPaths.of(buildTarget, filesystem.getBuckPaths()).getClassesDir();
  }

  public static RelPath getPathToOutputDir(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(
        filesystem.getBuckPaths(), buildTarget, "__%s_dummyrdotjava_output__");
  }

  public static RelPath getOutputJarPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return getPathToOutputDir(buildTarget, filesystem)
        .resolveRel(String.format("%s.jar", buildTarget.getShortNameAndFlavorPostfix()));
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputJar);
  }

  @Override
  public JavaAbiInfo getAbiInfo() {
    return javaAbiInfo;
  }

  @Override
  public Optional<BuildTarget> getAbiJar() {
    return Optional.of(getBuildTarget());
  }

  public RelPath getRDotJavaBinFolder() {
    return getRDotJavaBinFolder(getBuildTarget(), getProjectFilesystem());
  }

  public ImmutableList<HasAndroidResourceDeps> getAndroidResourceDeps() {
    return androidResourceDeps;
  }

  @VisibleForTesting
  CompileToJarStepFactory<?> getCompileStepFactory() {
    return compileStepFactory;
  }
}

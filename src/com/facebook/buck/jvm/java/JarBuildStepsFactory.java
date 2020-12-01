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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.CellPathResolverUtils;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.PathWrapper;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasCustomDepsLogic;
import com.facebook.buck.core.rules.common.RecordArtifactVerifier;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.DefaultBaseJavaAbiInfo;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.rules.modern.CustomFieldInputs;
import com.facebook.buck.rules.modern.CustomFieldSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Jar (java-archive) Build steps factory. */
public class JarBuildStepsFactory<T extends CompileToJarStepFactory.ExtraParams>
    implements AddsToRuleKey, RulePipelineStateFactory<JavacPipelineState> {

  private static final Path[] METADATA_DIRS =
      new Path[] {Paths.get("META-INF"), Paths.get("_STRIPPED_RESOURCES")};

  @CustomFieldBehavior(DefaultFieldSerialization.class)
  private final BuildTarget libraryTarget;

  @AddToRuleKey private final CompileToJarStepFactory<T> configuredCompiler;

  @AddToRuleKey private final Javac javac;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey private final ResourcesParameters resourcesParameters;

  @AddToRuleKey private final Optional<SourcePath> manifestFile;
  @AddToRuleKey private final ImmutableList<String> postprocessClassesCommands;

  @AddToRuleKey private final DependencyInfoHolder dependencyInfos;
  @AddToRuleKey private final ZipArchiveDependencySupplier abiClasspath;

  @AddToRuleKey private final boolean trackClassUsage;

  @CustomFieldBehavior(DefaultFieldSerialization.class)
  private final boolean trackJavacPhaseEvents;

  @AddToRuleKey private final boolean isRequiredForSourceOnlyAbi;
  @AddToRuleKey private final RemoveClassesPatternsMatcher classesToRemoveFromJar;

  @AddToRuleKey private final AbiGenerationMode abiGenerationMode;
  @AddToRuleKey private final AbiGenerationMode abiCompatibilityMode;
  @AddToRuleKey private final boolean withDownwardApi;

  /** Creates {@link JarBuildStepsFactory} */
  public static <T extends CompileToJarStepFactory.ExtraParams> JarBuildStepsFactory<T> of(
      BuildTarget libraryTarget,
      CompileToJarStepFactory<T> configuredCompiler,
      Javac javac,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ResourcesParameters resourcesParameters,
      Optional<SourcePath> manifestFile,
      ImmutableList<String> postprocessClassesCommands,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      RemoveClassesPatternsMatcher classesToRemoveFromJar,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      ImmutableList<JavaDependencyInfo> dependencyInfos,
      boolean isRequiredForSourceOnlyAbi,
      boolean withDownwardApi) {
    return new JarBuildStepsFactory<>(
        libraryTarget,
        configuredCompiler,
        javac,
        srcs,
        resources,
        resourcesParameters,
        manifestFile,
        postprocessClassesCommands,
        trackClassUsage,
        trackJavacPhaseEvents,
        classesToRemoveFromJar,
        abiGenerationMode,
        abiCompatibilityMode,
        dependencyInfos,
        isRequiredForSourceOnlyAbi,
        withDownwardApi);
  }

  /** Contains information about a Java classpath dependency. */
  public static class JavaDependencyInfo implements AddsToRuleKey {

    @AddToRuleKey public final SourcePath compileTimeJar;
    @AddToRuleKey public final SourcePath abiJar;
    @AddToRuleKey public final boolean isRequiredForSourceOnlyAbi;

    public JavaDependencyInfo(
        SourcePath compileTimeJar, SourcePath abiJar, boolean isRequiredForSourceOnlyAbi) {
      this.compileTimeJar = compileTimeJar;
      this.abiJar = abiJar;
      this.isRequiredForSourceOnlyAbi = isRequiredForSourceOnlyAbi;
    }
  }

  private static class DependencyInfoHolder implements AddsToRuleKey, HasCustomDepsLogic {

    // TODO(cjhopman): This is pretty much all due to these things caching all AddsToRuleKey things,
    // but we don't want that for these things because nobody else uses them. We should improve the
    // rulekey and similar stuff to better handle this.
    @ExcludeFromRuleKey(
        reason =
            "Adding this to the rulekey is slow for large projects and the abiClasspath already "
                + " reflects all the inputs. For the same reason, we need to do custom inputs"
                + " derivation and custom serialization.",
        serialization = InfosBehavior.class,
        inputs = InfosBehavior.class)
    private final ImmutableList<JavaDependencyInfo> infos;

    public DependencyInfoHolder(ImmutableList<JavaDependencyInfo> infos) {
      this.infos = infos;
    }

    @Override
    public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
      Stream.Builder<BuildRule> builder = Stream.builder();
      infos.forEach(
          info ->
              ruleFinder
                  .filterBuildRuleInputs(info.abiJar, info.compileTimeJar)
                  .forEach(builder::add));
      return builder.build();
    }

    public ZipArchiveDependencySupplier getAbiClasspath() {
      return new ZipArchiveDependencySupplier(
          this.infos.stream()
              .map(i -> i.abiJar)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    }

    public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
      return infos.stream()
          .map(info -> info.compileTimeJar)
          .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    }

    private static class InfosBehavior
        implements CustomFieldSerialization<ImmutableList<JavaDependencyInfo>>,
            CustomFieldInputs<ImmutableList<JavaDependencyInfo>> {

      @Override
      public <E extends Exception> void serialize(
          ImmutableList<JavaDependencyInfo> value, ValueVisitor<E> serializer) throws E {
        serializer.visitInteger(value.size());
        for (JavaDependencyInfo info : value) {
          serializer.visitSourcePath(info.compileTimeJar);
          serializer.visitSourcePath(info.abiJar);
          serializer.visitBoolean(info.isRequiredForSourceOnlyAbi);
        }
      }

      @Override
      public <E extends Exception> ImmutableList<JavaDependencyInfo> deserialize(
          ValueCreator<E> deserializer) throws E {
        int size = deserializer.createInteger();
        ImmutableList.Builder<JavaDependencyInfo> infos =
            ImmutableList.builderWithExpectedSize(size);
        for (int i = 0; i < size; i++) {
          SourcePath compileTimeJar = deserializer.createSourcePath();
          SourcePath abiJar = deserializer.createSourcePath();
          boolean isRequiredForSourceOnlyAbi = deserializer.createBoolean();
          infos.add(new JavaDependencyInfo(compileTimeJar, abiJar, isRequiredForSourceOnlyAbi));
        }
        return infos.build();
      }

      @Override
      public void getInputs(
          ImmutableList<JavaDependencyInfo> value, Consumer<SourcePath> consumer) {
        for (JavaDependencyInfo info : value) {
          consumer.accept(info.abiJar);
          consumer.accept(info.compileTimeJar);
        }
      }
    }
  }

  private JarBuildStepsFactory(
      BuildTarget libraryTarget,
      CompileToJarStepFactory<T> configuredCompiler,
      Javac javac,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ResourcesParameters resourcesParameters,
      Optional<SourcePath> manifestFile,
      ImmutableList<String> postprocessClassesCommands,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      RemoveClassesPatternsMatcher classesToRemoveFromJar,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      ImmutableList<JavaDependencyInfo> dependencyInfos,
      boolean isRequiredForSourceOnlyAbi,
      boolean withDownwardApi) {
    this.libraryTarget = libraryTarget;
    this.configuredCompiler = configuredCompiler;
    this.javac = javac;
    this.srcs = srcs;
    this.resources = resources;
    this.resourcesParameters = resourcesParameters;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.manifestFile = manifestFile;
    this.trackClassUsage = trackClassUsage;
    this.trackJavacPhaseEvents = trackJavacPhaseEvents;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
    this.abiGenerationMode = abiGenerationMode;
    this.abiCompatibilityMode = abiCompatibilityMode;
    this.dependencyInfos = new DependencyInfoHolder(dependencyInfos);
    this.withDownwardApi = withDownwardApi;
    this.abiClasspath = this.dependencyInfos.getAbiClasspath();
    this.isRequiredForSourceOnlyAbi = isRequiredForSourceOnlyAbi;
  }

  public boolean producesJar() {
    return !srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent();
  }

  public ImmutableSortedSet<SourcePath> getSources() {
    return srcs;
  }

  public ImmutableSortedSet<SourcePath> getResources() {
    return resources;
  }

  public Optional<String> getResourcesRoot() {
    return resourcesParameters.getResourcesRoot();
  }

  @Nullable
  public SourcePath getSourcePathToOutput(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return getOutputJarPath(buildTarget, filesystem)
        .map(path -> ExplicitBuildTargetSourcePath.of(buildTarget, path))
        .orElse(null);
  }

  @Nullable
  public SourcePath getSourcePathToGeneratedAnnotationPath(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return getGeneratedAnnotationPath(buildTarget, filesystem)
        .map(path -> ExplicitBuildTargetSourcePath.of(buildTarget, path))
        .orElse(null);
  }

  @VisibleForTesting
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return dependencyInfos.getCompileTimeClasspathSourcePaths();
  }

  public boolean useDependencyFileRuleKeys() {
    return !srcs.isEmpty() && trackClassUsage;
  }

  /** Returns a predicate indicating whether a SourcePath is covered by the depfile. */
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathRuleFinder ruleFinder) {
    // a hash set is intentionally used to achieve constant time look-up
    // TODO(cjhopman): This could probably be changed to be a 2-level check of archivepath->inner,
    // withinarchivepath->boolean.
    return abiClasspath.getArchiveMembers(ruleFinder).collect(ImmutableSet.toImmutableSet())
        ::contains;
  }

  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    // Annotation processors might enumerate all files under a certain path and then generate
    // code based on that list (without actually reading the files), making the list of files
    // itself a used dependency that must be part of the dependency-based key. We don't
    // currently have the instrumentation to detect such enumeration perfectly, but annotation
    // processors are most commonly looking for files under META-INF, so as a stopgap we add
    // the listing of META-INF to the rule key.
    return (SourcePath path) -> {
      if (!(path instanceof ArchiveMemberSourcePath)) {
        return false;
      }
      Path memberPath = ((ArchiveMemberSourcePath) path).getMemberPath();
      for (Path metadataPath : METADATA_DIRS) {
        if (memberPath.startsWith(metadataPath)) {
          return true;
        }
      }
      return false;
    };
  }

  public boolean useRulePipelining() {
    return configuredCompiler instanceof JavacToJarStepFactory
        && abiGenerationMode.isSourceAbi()
        && abiGenerationMode.usesDependencies();
  }

  /**
   * Returns build steps for ABI jar as list of {@link
   * com.facebook.buck.step.isolatedsteps.IsolatedStep}s.
   */
  public ImmutableList<IsolatedStep> getBuildStepsForAbiJar(
      BuildContext context,
      ProjectFilesystem filesystem,
      RecordArtifactVerifier buildableContext,
      BuildTarget buildTarget) {
    Preconditions.checkState(producesJar());
    Preconditions.checkArgument(
        buildTarget.equals(JavaAbis.getSourceAbiJar(libraryTarget))
            || buildTarget.equals(JavaAbis.getSourceOnlyAbiJar(libraryTarget)));
    ImmutableList.Builder<IsolatedStep> steps = ImmutableList.builder();

    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    ImmutableSortedSet<Path> compileTimeClasspathPaths =
        getCompileTimeClasspathPaths(sourcePathResolver);
    ImmutableSortedSet<Path> javaSrcs = getJavaSrcs(filesystem, sourcePathResolver);
    AbsPath rootPath = filesystem.getRootPath();
    BaseBuckPaths baseBuckPaths = filesystem.getBuckPaths();

    ImmutableList<BaseJavaAbiInfo> fullJarInfos =
        dependencyInfos.infos.stream()
            .map(this::toBaseJavaAbiInfo)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<BaseJavaAbiInfo> abiJarInfos =
        dependencyInfos.infos.stream()
            .filter(info -> info.isRequiredForSourceOnlyAbi)
            .map(this::toBaseJavaAbiInfo)
            .collect(ImmutableList.toImmutableList());

    CompilerOutputPaths compilerOutputPaths = CompilerOutputPaths.of(buildTarget, baseBuckPaths);
    ImmutableMap<RelPath, RelPath> resourcesMap =
        CopyResourcesStep.getResourcesMap(
            context,
            filesystem,
            compilerOutputPaths.getClassesDir().getPath(),
            resourcesParameters,
            buildTarget);

    ImmutableMap<String, RelPath> cellToPathMappings =
        CellPathResolverUtils.getCellToPathMappings(rootPath, context.getCellPathResolver());

    JarParameters abiJarParameters =
        getAbiJarParameters(buildTarget, context, filesystem, compilerOutputPaths).orElse(null);
    JarParameters libraryJarParameters =
        getLibraryJarParameters(context, filesystem, compilerOutputPaths).orElse(null);

    Path buildCellRootPath = context.getBuildCellRootPath();

    CompilerParameters compilerParameters =
        getCompilerParameters(
            compileTimeClasspathPaths,
            javaSrcs,
            fullJarInfos,
            abiJarInfos,
            buildTarget,
            trackClassUsage,
            trackJavacPhaseEvents,
            abiGenerationMode,
            abiCompatibilityMode,
            isRequiredForSourceOnlyAbi,
            compilerOutputPaths);

    ResolvedJavac resolvedJavac = javac.resolve(sourcePathResolver);

    configuredCompiler.createCompileToJarStep(
        filesystem,
        buildTarget,
        compilerParameters,
        ImmutableList.of(),
        abiJarParameters,
        libraryJarParameters,
        steps,
        buildableContext,
        withDownwardApi,
        cellToPathMappings,
        resourcesMap,
        buildCellRootPath,
        resolvedJavac,
        createExtraParams(context, rootPath));

    return steps.build();
  }

  private T createExtraParams(BuildContext context, AbsPath rootPath) {
    CompileToJarStepFactory.ExtraParams extraParams;
    if (configuredCompiler.supportsCompilationDaemon()) {
      JavacToJarStepFactory javacToJarStepFactory = (JavacToJarStepFactory) configuredCompiler;
      extraParams =
          javacToJarStepFactory.createExtraParams(context.getSourcePathResolver(), rootPath);
    } else {
      extraParams = BuildContextAwareExtraParams.of(context);
    }

    Class<T> extraParamsType = configuredCompiler.getExtraParamsType();
    return extraParamsType.cast(extraParams);
  }

  /**
   * Returns pipeline build steps for ABI jar as list of {@link
   * com.facebook.buck.step.isolatedsteps.IsolatedStep}s.
   */
  public ImmutableList<IsolatedStep> getPipelinedBuildStepsForAbiJar(
      BuildTarget buildTarget,
      BuildContext context,
      ProjectFilesystem filesystem,
      RecordArtifactVerifier buildableContext,
      JavacPipelineState state) {
    ImmutableList.Builder<IsolatedStep> steps = ImmutableList.builder();

    ImmutableMap<RelPath, RelPath> resourcesMap =
        CopyResourcesStep.getResourcesMap(
            context,
            filesystem,
            state.getCompilerParameters().getOutputPaths().getClassesDir().getPath(),
            resourcesParameters,
            buildTarget);

    ImmutableMap<String, RelPath> cellToPathMappings =
        CellPathResolverUtils.getCellToPathMappings(
            filesystem.getRootPath(), context.getCellPathResolver());

    ((JavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            filesystem,
            cellToPathMappings,
            buildTarget,
            state,
            postprocessClassesCommands,
            steps,
            buildableContext,
            resourcesMap);
    return steps.build();
  }

  /** Adds build steps for library jar */
  public void addBuildStepsForLibraryJar(
      BuildContext context,
      ProjectFilesystem filesystem,
      RecordArtifactVerifier buildableContext,
      BuildTarget buildTarget,
      RelPath pathToClassHashes,
      ImmutableList.Builder<IsolatedStep> steps) {
    Preconditions.checkArgument(buildTarget.equals(libraryTarget));

    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    ImmutableSortedSet<Path> compileTimeClasspathPaths =
        getCompileTimeClasspathPaths(sourcePathResolver);
    ImmutableSortedSet<Path> javaSrcs = getJavaSrcs(filesystem, sourcePathResolver);
    AbsPath rootPath = filesystem.getRootPath();
    BaseBuckPaths baseBuckPaths = filesystem.getBuckPaths();

    ImmutableList<BaseJavaAbiInfo> fullJarInfos =
        dependencyInfos.infos.stream()
            .map(this::toBaseJavaAbiInfo)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<BaseJavaAbiInfo> abiJarInfos =
        dependencyInfos.infos.stream()
            .filter(info -> info.isRequiredForSourceOnlyAbi)
            .map(this::toBaseJavaAbiInfo)
            .collect(ImmutableList.toImmutableList());

    CompilerOutputPaths compilerOutputPaths = CompilerOutputPaths.of(buildTarget, baseBuckPaths);

    ImmutableMap<RelPath, RelPath> resourcesMap =
        CopyResourcesStep.getResourcesMap(
            context,
            filesystem,
            compilerOutputPaths.getClassesDir().getPath(),
            resourcesParameters,
            buildTarget);

    ImmutableMap<String, RelPath> cellToPathMappings =
        CellPathResolverUtils.getCellToPathMappings(rootPath, context.getCellPathResolver());

    JarParameters libraryJarParameters =
        getLibraryJarParameters(context, filesystem, compilerOutputPaths).orElse(null);

    Path buildCellRootPath = context.getBuildCellRootPath();

    CompilerParameters compilerParameters =
        getCompilerParameters(
            compileTimeClasspathPaths,
            javaSrcs,
            fullJarInfos,
            abiJarInfos,
            buildTarget,
            trackClassUsage,
            trackJavacPhaseEvents,
            abiGenerationMode,
            abiCompatibilityMode,
            isRequiredForSourceOnlyAbi,
            compilerOutputPaths);

    ResolvedJavac resolvedJavac = javac.resolve(sourcePathResolver);

    configuredCompiler.createCompileToJarStep(
        filesystem,
        buildTarget,
        compilerParameters,
        postprocessClassesCommands,
        null,
        libraryJarParameters,
        steps,
        buildableContext,
        withDownwardApi,
        cellToPathMappings,
        resourcesMap,
        buildCellRootPath,
        resolvedJavac,
        createExtraParams(context, rootPath));

    JavaLibraryRules.addAccumulateClassNamesStep(
        filesystem.getIgnoredPaths(),
        steps,
        Optional.ofNullable(getSourcePathToOutput(buildTarget, filesystem))
            .map(sourcePath -> context.getSourcePathResolver().getCellUnsafeRelPath(sourcePath)),
        pathToClassHashes);
  }

  /** Adds pipeline build steps for library jar */
  public void addPipelinedBuildStepsForLibraryJar(
      BuildContext context,
      ProjectFilesystem filesystem,
      RecordArtifactVerifier buildableContext,
      JavacPipelineState state,
      RelPath pathToClassHashes,
      ImmutableList.Builder<IsolatedStep> steps) {

    ImmutableMap<RelPath, RelPath> resourcesMap =
        CopyResourcesStep.getResourcesMap(
            context,
            filesystem,
            state.getCompilerParameters().getOutputPaths().getClassesDir().getPath(),
            resourcesParameters,
            libraryTarget);

    ImmutableMap<String, RelPath> cellToPathMappings =
        CellPathResolverUtils.getCellToPathMappings(
            filesystem.getRootPath(), context.getCellPathResolver());

    ((JavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            filesystem,
            cellToPathMappings,
            libraryTarget,
            state,
            postprocessClassesCommands,
            steps,
            buildableContext,
            resourcesMap);

    JavaLibraryRules.addAccumulateClassNamesStep(
        filesystem.getIgnoredPaths(),
        steps,
        Optional.ofNullable(getSourcePathToOutput(libraryTarget, filesystem))
            .map(sourcePath -> context.getSourcePathResolver().getCellUnsafeRelPath(sourcePath)),
        pathToClassHashes);
  }

  private static CompilerParameters getCompilerParameters(
      ImmutableSortedSet<Path> compileTimeClasspathPaths,
      ImmutableSortedSet<Path> javaSrcs,
      ImmutableList<BaseJavaAbiInfo> fullJarInfos,
      ImmutableList<BaseJavaAbiInfo> abiJarInfos,
      BuildTarget buildTarget,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      boolean isRequiredForSourceOnlyAbi,
      CompilerOutputPaths compilerOutputPaths) {
    return CompilerParameters.builder()
        .setClasspathEntries(compileTimeClasspathPaths)
        .setSourceFilePaths(javaSrcs)
        .setOutputPaths(compilerOutputPaths)
        .setShouldTrackClassUsage(trackClassUsage)
        .setShouldTrackJavacPhaseEvents(trackJavacPhaseEvents)
        .setAbiGenerationMode(abiGenerationMode)
        .setAbiCompatibilityMode(abiCompatibilityMode)
        .setSourceOnlyAbiRuleInfoFactory(
            DefaultSourceOnlyAbiRuleInfoFactory.of(
                fullJarInfos, abiJarInfos, buildTarget, isRequiredForSourceOnlyAbi))
        .build();
  }

  private BaseJavaAbiInfo toBaseJavaAbiInfo(JavaDependencyInfo info) {
    return new DefaultBaseJavaAbiInfo(
        DefaultJavaAbiInfo.extractBuildTargetFromSourcePath(info.compileTimeJar)
            .getUnflavoredBuildTarget()
            .toString());
  }

  private ImmutableSortedSet<Path> getJavaSrcs(
      ProjectFilesystem filesystem, SourcePathResolverAdapter sourcePathResolver) {
    ImmutableSortedSet<Path> javaSrcs =
        srcs.stream()
            .map(src -> filesystem.relativize(sourcePathResolver.getAbsolutePath(src)).getPath())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    return javaSrcs;
  }

  private ImmutableSortedSet<Path> getCompileTimeClasspathPaths(
      SourcePathResolverAdapter sourcePathResolver) {
    ImmutableSortedSet<Path> compileTimeClasspathPaths =
        sourcePathResolver.getAllAbsolutePaths(dependencyInfos.getCompileTimeClasspathSourcePaths())
            .stream()
            .map(PathWrapper::getPath)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
    return compileTimeClasspathPaths;
  }

  private Optional<JarParameters> getLibraryJarParameters(
      BuildContext context, ProjectFilesystem filesystem, CompilerOutputPaths compilerOutputPaths) {
    return getJarParameters(context, filesystem, libraryTarget, compilerOutputPaths);
  }

  private Optional<JarParameters> getAbiJarParameters(
      BuildTarget target,
      BuildContext context,
      ProjectFilesystem filesystem,
      CompilerOutputPaths compilerOutputPaths) {
    if (JavaAbis.isLibraryTarget(target)) {
      return Optional.empty();
    }
    Preconditions.checkState(
        JavaAbis.isSourceAbiTarget(target) || JavaAbis.isSourceOnlyAbiTarget(target));
    return getJarParameters(context, filesystem, target, compilerOutputPaths);
  }

  private Optional<JarParameters> getJarParameters(
      BuildContext context,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      CompilerOutputPaths compilerOutputPaths) {
    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    RelPath classesDir = compilerOutputPaths.getClassesDir();
    ImmutableSortedSet<RelPath> entriesToJar =
        ImmutableSortedSet.orderedBy(RelPath.comparator()).add(classesDir).build();
    Optional<RelPath> manifestRelFile =
        manifestFile.map(
            sourcePath -> sourcePathResolver.getCellUnsafeRelPath(filesystem, sourcePath));
    return getOutputJarPath(buildTarget, filesystem)
        .map(
            output ->
                JarParameters.builder()
                    .setEntriesToJar(entriesToJar)
                    .setManifestFile(manifestRelFile)
                    .setJarPath(RelPath.of(output))
                    .setRemoveEntryPredicate(classesToRemoveFromJar)
                    .build());
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget) {
    Preconditions.checkState(useDependencyFileRuleKeys());
    return DefaultClassUsageFileReader.loadFromFile(
        filesystem.getRootPath(),
        cellPathResolver,
        filesystem.getPathForRelativePath(getDepFileRelativePath(filesystem, buildTarget)),
        getDepOutputPathToAbiSourcePath(context.getSourcePathResolver(), ruleFinder));
  }

  private Optional<Path> getOutputJarPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    if (!producesJar()) {
      return Optional.empty();
    }

    if (JavaAbis.isSourceAbiTarget(buildTarget) || JavaAbis.isSourceOnlyAbiTarget(buildTarget)) {
      return Optional.of(CompilerOutputPaths.getAbiJarPath(buildTarget, filesystem.getBuckPaths()));
    } else if (JavaAbis.isLibraryTarget(buildTarget)) {
      return Optional.of(
          CompilerOutputPaths.getOutputJarPath(buildTarget, filesystem.getBuckPaths()));
    } else {
      throw new IllegalArgumentException();
    }
  }

  private Optional<Path> getGeneratedAnnotationPath(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    if (!hasAnnotationProcessing()) {
      return Optional.empty();
    }
    return Optional.of(
        CompilerOutputPaths.getAnnotationPath(buildTarget, filesystem.getBuckPaths()).getPath());
  }

  private Path getDepFileRelativePath(ProjectFilesystem filesystem, BuildTarget buildTarget) {
    return CompilerOutputPaths.getDepFilePath(buildTarget, filesystem.getBuckPaths());
  }

  private ImmutableMap<Path, SourcePath> getDepOutputPathToAbiSourcePath(
      SourcePathResolverAdapter pathResolver, SourcePathRuleFinder ruleFinder) {
    ImmutableMap.Builder<Path, SourcePath> pathToSourcePathMapBuilder = ImmutableMap.builder();
    for (JavaDependencyInfo depInfo : dependencyInfos.infos) {
      SourcePath sourcePath = depInfo.compileTimeJar;
      BuildRule rule = ruleFinder.getRule(sourcePath).get();
      AbsPath path = pathResolver.getAbsolutePath(sourcePath);
      if (rule instanceof HasJavaAbi && ((HasJavaAbi) rule).getAbiJar().isPresent()) {
        BuildTarget buildTarget = ((HasJavaAbi) rule).getAbiJar().get();
        pathToSourcePathMapBuilder.put(
            path.getPath(), DefaultBuildTargetSourcePath.of(buildTarget));
      }
    }
    return pathToSourcePathMapBuilder.build();
  }

  @Override
  public JavacPipelineState newInstance(
      BuildContext context, ProjectFilesystem filesystem, BuildTarget firstRule) {
    JavacToJarStepFactory javacToJarStepFactory = (JavacToJarStepFactory) configuredCompiler;

    SourcePathResolverAdapter sourcePathResolver = context.getSourcePathResolver();
    ImmutableSortedSet<Path> compileTimeClasspathPaths =
        getCompileTimeClasspathPaths(sourcePathResolver);
    ImmutableSortedSet<Path> javaSrcs = getJavaSrcs(filesystem, sourcePathResolver);
    BaseBuckPaths baseBuckPaths = filesystem.getBuckPaths();

    ImmutableList<BaseJavaAbiInfo> fullJarInfos =
        dependencyInfos.infos.stream()
            .map(this::toBaseJavaAbiInfo)
            .collect(ImmutableList.toImmutableList());

    ImmutableList<BaseJavaAbiInfo> abiJarInfos =
        dependencyInfos.infos.stream()
            .filter(info -> info.isRequiredForSourceOnlyAbi)
            .map(this::toBaseJavaAbiInfo)
            .collect(ImmutableList.toImmutableList());

    CompilerOutputPaths compilerOutputPaths = CompilerOutputPaths.of(firstRule, baseBuckPaths);
    JarParameters abiJarParameters =
        getAbiJarParameters(firstRule, context, filesystem, compilerOutputPaths).orElse(null);
    JarParameters libraryJarParameters =
        getLibraryJarParameters(context, filesystem, compilerOutputPaths).orElse(null);

    CompilerParameters compilerParameters =
        getCompilerParameters(
            compileTimeClasspathPaths,
            javaSrcs,
            fullJarInfos,
            abiJarInfos,
            firstRule,
            trackClassUsage,
            trackJavacPhaseEvents,
            abiGenerationMode,
            abiCompatibilityMode,
            isRequiredForSourceOnlyAbi,
            compilerOutputPaths);

    ResolvedJavac resolvedJavac = javac.resolve(sourcePathResolver);

    return javacToJarStepFactory.createPipelineState(
        firstRule,
        compilerParameters,
        abiJarParameters,
        libraryJarParameters,
        withDownwardApi,
        resolvedJavac,
        javacToJarStepFactory.createExtraParams(sourcePathResolver, filesystem.getRootPath()));
  }

  boolean hasAnnotationProcessing() {
    return configuredCompiler.hasAnnotationProcessing();
  }
}

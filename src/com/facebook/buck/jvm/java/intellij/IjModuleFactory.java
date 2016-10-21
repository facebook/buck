/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyLibraryDescription;
import com.facebook.buck.jvm.groovy.GroovyTestDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds {@link IjModule}s out of {@link TargetNode}s.
 */
public class IjModuleFactory {

  /**
   * These target types are mapped onto .iml module files.
   */
  private static final ImmutableSet<BuildRuleType> SUPPORTED_MODULE_TYPES = ImmutableSet.of(
      AndroidBinaryDescription.TYPE,
      AndroidLibraryDescription.TYPE,
      AndroidResourceDescription.TYPE,
      CxxLibraryDescription.TYPE,
      JavaLibraryDescription.TYPE,
      JavaTestDescription.TYPE,
      RobolectricTestDescription.TYPE,
      GroovyLibraryDescription.TYPE,
      GroovyTestDescription.TYPE);

  public static final Predicate<TargetNode<?>> SUPPORTED_MODULE_TYPES_PREDICATE =
      input -> SUPPORTED_MODULE_TYPES.contains(input.getType());

  /**
   * Provides the {@link IjModuleFactory} with {@link Path}s to various elements of the project.
   */
  public interface IjModuleFactoryResolver {
    /**
     * @param targetNode node to generate the path to
     * @return  the project-relative path to a directory structure under which the R.class file can
     *     be found (the structure will be the same as the package path of the R class). A path
     *     should be returned only if the given TargetNode requires the R.class to compile.
     */
    Optional<Path> getDummyRDotJavaPath(TargetNode<?> targetNode);

    /**
     * @param targetNode node describing the Android binary to get the manifest of.
     * @return path on disk to the AndroidManifest.
     */
    Path getAndroidManifestPath(TargetNode<AndroidBinaryDescription.Arg> targetNode);

    /**
     * @param targetNode node describing the Android library to get the manifest of.
     * @return path on disk to the AndroidManifest.
     */
    Optional<Path> getLibraryAndroidManifestPath(
        TargetNode<AndroidLibraryDescription.Arg> targetNode);

    /**
     * @param targetNode node describing the Android binary to get the Proguard config of.
     * @return path on disk to the proguard config.
     */
    Optional<Path> getProguardConfigPath(TargetNode<AndroidBinaryDescription.Arg> targetNode);

    /**
     * @param targetNode node describing the Android resources to get the path of.
     * @return path on disk to the resources folder.
     */
    Optional<Path> getAndroidResourcePath(TargetNode<AndroidResourceDescription.Arg> targetNode);

    /**
     * @param targetNode node describing the Android assets to get the path of.
     * @return path on disk to the assets folder.
     */
    Optional<Path> getAssetsPath(TargetNode<AndroidResourceDescription.Arg> targetNode);

    /**
     * @param targetNode node which may use annotation processors.
     * @return path to the annotation processor output if any annotation proceessors are configured
     *        for the given node.
     */
    Optional<Path> getAnnotationOutputPath(TargetNode<? extends JvmLibraryArg> targetNode);
  }

  /**
   * Holds all of the mutable state required during {@link IjModule} creation.
   */
  private static class ModuleBuildContext {
    private final ImmutableSet<BuildTarget> circularDependencyInducingTargets;

    private Optional<IjModuleAndroidFacet.Builder> androidFacetBuilder;
    private ImmutableSet.Builder<Path> extraClassPathDependenciesBuilder;
    private ImmutableSet.Builder<IjFolder> generatedSourceCodeFoldersBuilder;
    private Map<Path, IjFolder> sourceFoldersMergeMap;
    // See comment in getDependencies for these two member variables.
    private Map<BuildTarget, IjModuleGraph.DependencyType> dependencyTypeMap;
    private Multimap<Path, BuildTarget> dependencyOriginMap;

    public ModuleBuildContext(ImmutableSet<BuildTarget> circularDependencyInducingTargets) {
      this.circularDependencyInducingTargets = circularDependencyInducingTargets;
      this.androidFacetBuilder = Optional.empty();
      this.extraClassPathDependenciesBuilder = new ImmutableSet.Builder<>();
      this.generatedSourceCodeFoldersBuilder = ImmutableSet.builder();
      this.sourceFoldersMergeMap = new HashMap<>();
      this.dependencyTypeMap = new HashMap<>();
      this.dependencyOriginMap = HashMultimap.create();
    }

    public void ensureAndroidFacetBuilder() {
      if (!androidFacetBuilder.isPresent()) {
        androidFacetBuilder = Optional.of(IjModuleAndroidFacet.builder());
      }
    }

    public IjModuleAndroidFacet.Builder getOrCreateAndroidFacetBuilder() {
      ensureAndroidFacetBuilder();
      return androidFacetBuilder.get();
    }

    public Optional<IjModuleAndroidFacet> getAndroidFacet() {
      return androidFacetBuilder.map(IjModuleAndroidFacet.Builder::build);
    }

    public ImmutableSet<IjFolder> getSourceFolders() {
      return ImmutableSet.copyOf(sourceFoldersMergeMap.values());
    }

    public void addExtraClassPathDependency(Path path) {
      extraClassPathDependenciesBuilder.add(path);
    }

    public ImmutableSet<Path> getExtraClassPathDependencies() {
      return extraClassPathDependenciesBuilder.build();
    }

    public void addGeneratedSourceCodeFolder(IjFolder generatedFolder) {
      generatedSourceCodeFoldersBuilder.add(generatedFolder);
    }

    public ImmutableSet<IjFolder> getGeneratedSourceCodeFolders() {
      return generatedSourceCodeFoldersBuilder.build();
    }

    /**
     * Adds a source folder to the context. If a folder with the same path has already been added
     * the types of the two folders will be merged.
     *
     * @param folder folder to add/merge.
     */
    public void addSourceFolder(IjFolder folder) {
      Path path = folder.getPath();
      IjFolder otherFolder = sourceFoldersMergeMap.get(path);
      if (otherFolder != null) {
        folder = mergeAllowingTestToBePromotedToSource(folder, otherFolder);
      }
      sourceFoldersMergeMap.put(path, folder);
    }

    private IjFolder mergeAllowingTestToBePromotedToSource(IjFolder from, IjFolder to) {
      if ((from instanceof TestFolder && to instanceof SourceFolder) ||
          (to instanceof TestFolder && from instanceof SourceFolder)) {
        return new SourceFolder(
            to.getPath(),
            from.getWantsPackagePrefix() || to.getWantsPackagePrefix(),
            IjFolder.combineInputs(from, to)
        );
      }

      Preconditions.checkArgument(from.getClass() == to.getClass());

      return from.merge(to);
    }

    public void addDeps(
        ImmutableSet<BuildTarget> buildTargets,
        IjModuleGraph.DependencyType dependencyType) {
      addDeps(ImmutableSet.of(), buildTargets, dependencyType);
    }

    public void addCompileShadowDep(BuildTarget buildTarget) {
      IjModuleGraph.DependencyType.putWithMerge(
          dependencyTypeMap,
          buildTarget,
          IjModuleGraph.DependencyType.COMPILED_SHADOW);
    }

    /**
     * Record a dependency on a {@link BuildTarget}. The dependency's type will be merged if
     * multiple {@link TargetNode}s refer to it or if multiple TargetNodes include sources from
     * the same directory.
     *
     * @param sourcePaths the {@link Path}s to sources which need this dependency to build.
     *                    Can be empty.
     * @param buildTargets the {@link BuildTarget}s to depend on
     * @param dependencyType what is the dependency needed for.
     */
    public void addDeps(
        ImmutableSet<Path> sourcePaths,
        ImmutableSet<BuildTarget> buildTargets,
        IjModuleGraph.DependencyType dependencyType) {
      for (BuildTarget buildTarget : buildTargets) {
        if (circularDependencyInducingTargets.contains(buildTarget)) {
          continue;
        }
        if (sourcePaths.isEmpty()) {
          IjModuleGraph.DependencyType.putWithMerge(dependencyTypeMap, buildTarget, dependencyType);
        } else {
          for (Path sourcePath : sourcePaths) {
            dependencyOriginMap.put(sourcePath, buildTarget);
          }
        }
      }
    }

    public ImmutableMap<BuildTarget, IjModuleGraph.DependencyType> getDependencies() {
      // Some targets may introduce dependencies without contributing to the IjFolder set. These
      // are recorded in the dependencyTypeMap.
      // Dependencies associated with source paths inherit the type from the folder. This is because
      // IntelliJ only operates on folders and so it is impossible to distinguish between test and
      // production code if it's in the same folder. That in turn means test-only dependencies need
      // to be "promoted" to production dependencies in the above scenario to keep code compiling.
      // It is also possible that a target is included in both maps, in which case the type gets
      // merged anyway.
      // Merging types does not back-propagate: if TargetA depends on TargetB and the type of
      // TargetB has been changed that does not mean the dependency type of TargetA is changed too.
      Map<BuildTarget, IjModuleGraph.DependencyType> result = new HashMap<>(dependencyTypeMap);
      for (Path path : dependencyOriginMap.keySet()) {
        IjModuleGraph.DependencyType dependencyType =
            Preconditions.checkNotNull(sourceFoldersMergeMap.get(path)) instanceof TestFolder ?
                IjModuleGraph.DependencyType.TEST :
                IjModuleGraph.DependencyType.PROD;
        for (BuildTarget buildTarget : dependencyOriginMap.get(path)) {
          IjModuleGraph.DependencyType.putWithMerge(result, buildTarget, dependencyType);
        }
      }
      return ImmutableMap.copyOf(result);
    }
  }

  /**
   * Rule describing which aspects of the supplied {@link TargetNode} to transfer to the
   * {@link IjModule} being constructed.
   *
   * @param <T> TargetNode type.
   */
  private interface IjModuleRule<T> {
    BuildRuleType getType();
    void apply(TargetNode<T> targetNode, ModuleBuildContext context);
  }

  private static final String SDK_TYPE_JAVA = "JavaSDK";

  private final Map<BuildRuleType, IjModuleRule<?>> moduleRuleIndex = new HashMap<>();
  private final IjModuleFactoryResolver moduleFactoryResolver;
  private final IjProjectConfig projectConfig;
  private final boolean excludeShadows;
  private final boolean autogenerateAndroidFacetSources;

  /**
   * @param moduleFactoryResolver see {@link IjModuleFactoryResolver}.
   */
  public IjModuleFactory(
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig,
      boolean excludeShadows) {
    this.excludeShadows = excludeShadows;
    this.projectConfig = projectConfig;
    this.autogenerateAndroidFacetSources = projectConfig.isAutogenerateAndroidFacetSourcesEnabled();

    addToIndex(new AndroidBinaryModuleRule());
    addToIndex(new AndroidLibraryModuleRule());
    addToIndex(new AndroidResourceModuleRule());
    addToIndex(new CxxLibraryModuleRule());
    addToIndex(new JavaLibraryModuleRule());
    addToIndex(new JavaTestModuleRule());
    addToIndex(new RobolectricTestModuleRule());
    addToIndex(new GroovyLibraryModuleRule());
    addToIndex(new GroovyTestModuleRule());

    this.moduleFactoryResolver = moduleFactoryResolver;

    Preconditions.checkState(
        moduleRuleIndex.keySet().equals(SUPPORTED_MODULE_TYPES));
  }

  private void addToIndex(IjModuleRule<?> rule) {
    Preconditions.checkArgument(!moduleRuleIndex.containsKey(rule.getType()));
    Preconditions.checkArgument(SUPPORTED_MODULE_TYPES.contains(rule.getType()));
    moduleRuleIndex.put(rule.getType(), rule);
  }

  /**
   * Create an {@link IjModule} form the supplied parameters.
   *
   * @param moduleBasePath the top-most directory the module is responsible for.
   * @param targetNodes set of nodes the module is to be created from.
   * @return nice shiny new module.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public IjModule createModule(
      Path moduleBasePath,
      ImmutableSet<TargetNode<?>> targetNodes) {
    Preconditions.checkArgument(!targetNodes.isEmpty());


    ImmutableSet<BuildTarget> moduleBuildTargets = targetNodes.stream()
        .map(HasBuildTarget::getBuildTarget)
        .collect(MoreCollectors.toImmutableSet());

    ModuleBuildContext context = new ModuleBuildContext(moduleBuildTargets);

    for (TargetNode<?> targetNode : targetNodes) {
      IjModuleRule<?> rule = Preconditions.checkNotNull(moduleRuleIndex.get(targetNode.getType()));
      rule.apply((TargetNode) targetNode, context);
    }

    Optional<String> sourceLevel = getSourceLevel(targetNodes);
    // The only JDK type that is supported right now. If we ever add support for Android libraries
    // to have different language levels we need to add logic to detect correct JDK type.
    String sdkType = SDK_TYPE_JAVA;

    Optional<String> sdkName;
    if (sourceLevel.isPresent()) {
      sdkName = getSdkName(sourceLevel.get(), sdkType);
    } else {
      sdkName = Optional.empty();
    }

    return IjModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(targetNodes)
        .addAllFolders(context.getSourceFolders())
        .putAllDependencies(context.getDependencies())
        .setAndroidFacet(context.getAndroidFacet())
        .addAllExtraClassPathDependencies(context.getExtraClassPathDependencies())
        .addAllGeneratedSourceCodeFolders(context.getGeneratedSourceCodeFolders())
        .setSdkName(sdkName)
        .setSdkType(sdkType)
        .setLanguageLevel(sourceLevel)
        .build();
  }

  private Optional<String> getSourceLevel(
      Iterable<TargetNode<?>> targetNodes) {
    Optional<String> result = Optional.empty();
    for (TargetNode<?> targetNode : targetNodes) {
      BuildRuleType type = targetNode.getType();
      if (!type.equals(JavaLibraryDescription.TYPE)) {
        continue;
      }

      JavacOptions defaultJavacOptions = projectConfig.getJavaBuckConfig().getDefaultJavacOptions();
      String defaultSourceLevel = defaultJavacOptions.getSourceLevel();
      String defaultTargetLevel = defaultJavacOptions.getTargetLevel();
      JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) targetNode.getConstructorArg();
      if (!defaultSourceLevel.equals(arg.source.orElse(defaultSourceLevel)) ||
          !defaultTargetLevel.equals(arg.target.orElse(defaultTargetLevel))) {
        result = arg.source;
      }
    }

    if (result.isPresent()) {
      result = Optional.of(normalizeSourceLevel(result.get()));
    }

    return result;
  }

  /**
   * Ensures that source level has format "majorVersion.minorVersion".
   */
  private static String normalizeSourceLevel(String jdkVersion) {
    if (jdkVersion.length() == 1) {
      return "1." + jdkVersion;
    } else {
      return jdkVersion;
    }
  }

  private Optional<String> getSdkName(String sourceLevel, String sdkType) {
    Optional<String> sdkName = Optional.empty();
    if (SDK_TYPE_JAVA.equals(sdkType)) {
      sdkName = projectConfig.getJavaLibrarySdkNameForSourceLevel(sourceLevel);
    }
    return sdkName.isPresent() ? sdkName : Optional.of(sourceLevel);
  }

  /**
   * Calculate the set of directories containing inputs to the target.
   *
   * @param paths inputs to a given target.
   * @return index of path to set of inputs in that path
   */
  private static ImmutableMultimap<Path, Path> getSourceFoldersToInputsIndex(
      ImmutableSet<Path> paths) {
    return FluentIterable.from(paths)
        .index(
            input -> {
              Path parent = input.getParent();
              if (parent == null) {
                return Paths.get("");
              }
              return parent;
            });
  }

  /**
   * @param paths paths to check
   * @return whether any of the paths pointed to something not in the source tree.
   */
  private static boolean containsNonSourcePath(Iterable<SourcePath> paths) {
    return FluentIterable.from(paths)
        .anyMatch(
            input -> !(input instanceof PathSourcePath));
  }

  /**
   * Add the set of input paths to the {@link IjModule.Builder} as source folders.
   *
   * @param foldersToInputsIndex mapping of source folders to their inputs.
   * @param wantsPackagePrefix whether folders should be annotated with a package prefix. This
   *                           only makes sense when the source folder is Java source code.
   * @param context the module to add the folders to.
   */
  private static void addSourceFolders(
      IjFolder.IJFolderFactory factory,
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    for (Map.Entry<Path, Collection<Path>> entry : foldersToInputsIndex.asMap().entrySet()) {
      context.addSourceFolder(
          factory.create(
            entry.getKey(),
            wantsPackagePrefix,
            FluentIterable.from(entry.getValue()).toSortedSet(Ordering.natural())
        )
      );
    }
  }

  private void addDepsAndFolder(
      IjFolder.IJFolderFactory folderFactory,
      IjModuleGraph.DependencyType dependencyType,
      TargetNode<?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context,
      ImmutableSet<Path> inputPaths
  ) {
    ImmutableMultimap<Path, Path> foldersToInputsIndex = getSourceFoldersToInputsIndex(inputPaths);
    addSourceFolders(folderFactory, foldersToInputsIndex, wantsPackagePrefix, context);
    addDeps(foldersToInputsIndex, targetNode, dependencyType, context);

    if (targetNode.getConstructorArg() instanceof JvmLibraryArg) {
      addAnnotationOutputIfNeeded(folderFactory, targetNode, context);
    }
  }

  private void addDepsAndFolder(
      IjFolder.IJFolderFactory folderFactory,
      IjModuleGraph.DependencyType dependencyType,
      TargetNode<?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context
  ) {
    addDepsAndFolder(
        folderFactory,
        dependencyType,
        targetNode,
        wantsPackagePrefix,
        context,
        targetNode.getInputs());
  }

  private void addDepsAndSources(
      TargetNode<?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    addDepsAndFolder(
        SourceFolder.FACTORY,
        IjModuleGraph.DependencyType.PROD,
        targetNode,
        wantsPackagePrefix,
        context);
  }

  private void addDepsAndTestSources(
      TargetNode<?> targetNode,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    addDepsAndFolder(
        TestFolder.FACTORY,
        IjModuleGraph.DependencyType.TEST,
        targetNode,
        wantsPackagePrefix,
        context);
  }

  private static void addDeps(
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      TargetNode<?> targetNode,
      IjModuleGraph.DependencyType dependencyType,
      ModuleBuildContext context) {
    context.addDeps(
        foldersToInputsIndex.keySet(),
        targetNode.getDeps(),
        dependencyType);
  }

  private <T extends JavaLibraryDescription.Arg> void addCompiledShadowIfNeeded(
      TargetNode<T> targetNode,
      ModuleBuildContext context) {
    if (excludeShadows) {
      return;
    }

    T arg = targetNode.getConstructorArg();
    // TODO(marcinkosiba): investigate supporting annotation processors without resorting to this.
    boolean hasAnnotationProcessors = !arg.annotationProcessors.isEmpty();
    if (containsNonSourcePath(arg.srcs) || hasAnnotationProcessors) {
      context.addCompileShadowDep(targetNode.getBuildTarget());
    }
  }

  @SuppressWarnings("unchecked")
  private void addAnnotationOutputIfNeeded(
      IjFolder.IJFolderFactory folderFactory,
      TargetNode<?> targetNode,
      ModuleBuildContext context) {
    TargetNode<? extends JvmLibraryArg> jvmLibraryTargetNode =
        (TargetNode<? extends JvmLibraryArg>) targetNode;

    Optional<Path> annotationOutput =
        moduleFactoryResolver.getAnnotationOutputPath(jvmLibraryTargetNode);
    if (!annotationOutput.isPresent()) {
      return;
    }

    Path annotationOutputPath = annotationOutput.get();
    context.addGeneratedSourceCodeFolder(
        folderFactory.create(
            annotationOutputPath,
            false,
            ImmutableSortedSet.of(annotationOutputPath))
    );
  }

  private class AndroidBinaryModuleRule
      implements IjModuleRule<AndroidBinaryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return AndroidBinaryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<AndroidBinaryDescription.Arg> target, ModuleBuildContext context) {
      context.addDeps(target.getDeps(), IjModuleGraph.DependencyType.PROD);

      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();
      androidFacetBuilder
          .setManifestPath(moduleFactoryResolver.getAndroidManifestPath(target))
          .setProguardConfigPath(moduleFactoryResolver.getProguardConfigPath(target))
          .setAutogenerateSources(autogenerateAndroidFacetSources)
          .setAndroidLibrary(false);
    }
  }

  private class AndroidLibraryModuleRule
      implements IjModuleRule<AndroidLibraryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return AndroidLibraryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<AndroidLibraryDescription.Arg> target,
        ModuleBuildContext context) {
      addDepsAndSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
      Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
      if (dummyRDotJavaClassPath.isPresent()) {
        context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
      }

      IjModuleAndroidFacet.Builder builder = context.getOrCreateAndroidFacetBuilder();
      Optional<Path> manifestPath = moduleFactoryResolver.getLibraryAndroidManifestPath(target);
      if (manifestPath.isPresent()) {
        builder.setManifestPath(manifestPath.get());
      }
      builder.setAutogenerateSources(autogenerateAndroidFacetSources);
      builder.setAndroidLibrary(true);
    }
  }

  private class AndroidResourceModuleRule
      implements IjModuleRule<AndroidResourceDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return AndroidResourceDescription.TYPE;
    }

    @Override
    public void apply(
        TargetNode<AndroidResourceDescription.Arg> target,
        ModuleBuildContext context) {

      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();
      androidFacetBuilder
          .setAutogenerateSources(autogenerateAndroidFacetSources)
          .setAndroidLibrary(true);

      Optional<Path> assets = moduleFactoryResolver.getAssetsPath(target);
      if (assets.isPresent()) {
        androidFacetBuilder.addAssetPaths(assets.get());
      }

      Optional<Path> resources = moduleFactoryResolver.getAndroidResourcePath(target);
      ImmutableSet<Path> resourceFolders;
      if (resources.isPresent()) {
        resourceFolders = ImmutableSet.of(resources.get());

        androidFacetBuilder.addAllResourcePaths(resourceFolders);

        for (Path resourceFolder : resourceFolders) {
          context.addSourceFolder(
              new AndroidResourceFolder(resourceFolder)
          );
        }
      } else {
        resourceFolders = ImmutableSet.of();
      }

      androidFacetBuilder.setPackageName(target.getConstructorArg().rDotJavaPackage);

      Optional<Path> dummyRDotJavaClassPath = moduleFactoryResolver.getDummyRDotJavaPath(target);
      if (dummyRDotJavaClassPath.isPresent()) {
        context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
      }
      context.getOrCreateAndroidFacetBuilder().setAndroidLibrary(true);

      context.addDeps(resourceFolders, target.getDeps(), IjModuleGraph.DependencyType.PROD);
    }
  }

  private class CxxLibraryModuleRule implements IjModuleRule<CxxLibraryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return CxxLibraryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<CxxLibraryDescription.Arg> target, ModuleBuildContext context) {
      addSourceFolders(
          SourceFolder.FACTORY,
          getSourceFoldersToInputsIndex(target.getInputs()),
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class JavaLibraryModuleRule implements IjModuleRule<JavaLibraryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return JavaLibraryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<JavaLibraryDescription.Arg> target, ModuleBuildContext context) {
      addDepsAndSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
    }
  }

  private class GroovyLibraryModuleRule implements IjModuleRule<GroovyLibraryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return GroovyLibraryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<GroovyLibraryDescription.Arg> target, ModuleBuildContext context) {
      addDepsAndSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class GroovyTestModuleRule implements IjModuleRule<GroovyTestDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return GroovyTestDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<GroovyTestDescription.Arg> target, ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          false /* wantsPackagePrefix */,
          context);
    }
  }

  private class JavaTestModuleRule implements IjModuleRule<JavaTestDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return JavaTestDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<JavaTestDescription.Arg> target, ModuleBuildContext context) {
      addDepsAndTestSources(
          target,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
    }
  }

  private class RobolectricTestModuleRule extends JavaTestModuleRule {

    @Override
    public BuildRuleType getType() {
      return RobolectricTestDescription.TYPE;
    }
  }
}

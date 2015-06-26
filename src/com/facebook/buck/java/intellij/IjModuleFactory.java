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

package com.facebook.buck.java.intellij;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
      JavaLibraryDescription.TYPE,
      JavaTestDescription.TYPE,
      RobolectricTestDescription.TYPE);

  public static final Predicate<TargetNode<?>> SUPPORTED_MODULE_TYPES_PREDICATE =
      new Predicate<TargetNode<?>>() {
        @Override
        public boolean apply(TargetNode<?> input) {
          return SUPPORTED_MODULE_TYPES.contains(input.getType());
        }
      };

  /**
   * Holds all of the mutable state required during {@link IjModule} creation.
   */
  private static class ModuleBuildContext {
    private final ImmutableSet<BuildTarget> circularDependencyInducingTargets;

    private Optional<IjModuleAndroidFacet.Builder> androidFacetBuilder;
    private ImmutableSet.Builder<Path> extraClassPathDependenciesBuilder;
    private Map<Path, IjFolder> sourceFoldersMergeMap;
    // See comment in getDependencies for these two member variables.
    private Map<BuildTarget, IjModuleGraph.DependencyType> dependencyTypeMap;
    private Multimap<Path, BuildTarget> dependencyOriginMap;

    public ModuleBuildContext(ImmutableSet<BuildTarget> circularDependencyInducingTargets) {
      this.circularDependencyInducingTargets = circularDependencyInducingTargets;
      this.androidFacetBuilder = Optional.absent();
      this.extraClassPathDependenciesBuilder = new ImmutableSet.Builder<>();
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
      return androidFacetBuilder.transform(
          new Function<IjModuleAndroidFacet.Builder, IjModuleAndroidFacet>() {
            @Override
            public IjModuleAndroidFacet apply(IjModuleAndroidFacet.Builder input) {
              return input.build();
            }
          });
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
        folder = folder.merge(otherFolder);
      }
      sourceFoldersMergeMap.put(path, folder);
    }

    public void addDeps(
        ImmutableSet<BuildTarget> buildTargets,
        IjModuleGraph.DependencyType dependencyType) {
      addDeps(ImmutableSet.<Path>of(), buildTargets, dependencyType);
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
            Preconditions.checkNotNull(sourceFoldersMergeMap.get(path)).isTest() ?
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

  private final Map<BuildRuleType, IjModuleRule<?>> moduleRuleIndex = new HashMap<>();
  private final Function<? super TargetNode<?>, Optional<Path>> dummyRDotJavaClassPathResolver;

  /**
   * @param dummyRDotJavaClassPathResolver function to find the project-relative path to a
   *                                       directory structure under which the R.class file can be
   *                                       found (the structure will be the same as the package path
   *                                       of the R class). A path should be returned only if the
   *                                       given TargetNode requires the R.class to compile.
   */
  public IjModuleFactory(
      Function<? super TargetNode<?>, Optional<Path>> dummyRDotJavaClassPathResolver) {
    addToIndex(new AndroidBinaryModuleRule());
    addToIndex(new AndroidLibraryModuleRule());
    addToIndex(new AndroidResourceModuleRule());
    addToIndex(new JavaLibraryModuleRule());
    addToIndex(new JavaTestModuleRule());
    addToIndex(new RobolectricTestModuleRule());

    this.dummyRDotJavaClassPathResolver = dummyRDotJavaClassPathResolver;

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
  public IjModule createModule(Path moduleBasePath, ImmutableSet<TargetNode<?>> targetNodes) {
    Preconditions.checkArgument(!targetNodes.isEmpty());


    ImmutableSet<BuildTarget> moduleBuildTargets = FluentIterable.from(targetNodes)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();

    ModuleBuildContext context = new ModuleBuildContext(moduleBuildTargets);

    for (TargetNode<?> targetNode : targetNodes) {
      IjModuleRule<?> rule = Preconditions.checkNotNull(moduleRuleIndex.get(targetNode.getType()));

      rule.apply((TargetNode) targetNode, context);
    }

    return IjModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(targetNodes)
        .addAllFolders(context.getSourceFolders())
        .putAllDependencies(context.getDependencies())
        .setAndroidFacet(context.getAndroidFacet())
        .addAllExtraClassPathDependencies(context.getExtraClassPathDependencies())
        .build();
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
            new Function<Path, Path>() {
              @Override
              public Path apply(Path input) {
                Path parent = input.getParent();
                if (parent == null) {
                  return Paths.get("");
                }
                return parent;
              }
            });
  }

  /**
   * @param paths paths to check
   * @return whether any of the paths pointed to something not in the source tree.
   */
  private static boolean containsNonSourcePath(Optional<? extends Iterable<SourcePath>> paths) {
    if (!paths.isPresent()) {
      return false;
    }
    return FluentIterable.from(paths.get())
        .anyMatch(
            new Predicate<SourcePath>() {
              @Override
              public boolean apply(SourcePath input) {
                return !(input instanceof PathSourcePath);
              }
            });
  }

  /**
   * Add the set of input paths to the {@link IjModule.Builder} as source folders.
   *
   * @param foldersToInputsIndex mapping of source folders to their inputs.
   * @param type folder type.
   * @param wantsPackagePrefix whether folders should be annotated with a package prefix. This
   *                           only makes sense when the source folder is Java source code.
   * @param context the module to add the folders to.
   */
  private static void addSourceFolders(
      ImmutableMultimap<Path, Path> foldersToInputsIndex,
      IjFolder.Type type,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    for (Map.Entry<Path, Collection<Path>> entry : foldersToInputsIndex.asMap().entrySet()) {
      context.addSourceFolder(
          IjFolder.builder()
              .setPath(entry.getKey())
              .setInputs(FluentIterable.from(entry.getValue()).toSortedSet(Ordering.natural()))
              .setType(type)
              .setWantsPackagePrefix(wantsPackagePrefix)
              .build());
    }
  }

  private static void addDepsAndSources(
      TargetNode<?> targetNode,
      boolean isTest,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    ImmutableMultimap<Path, Path> foldersToInputsIndex = getSourceFoldersToInputsIndex(
        targetNode.getInputs());
    addSourceFolders(
        foldersToInputsIndex,
        isTest ? IjFolder.Type.TEST_FOLDER : IjFolder.Type.SOURCE_FOLDER,
        wantsPackagePrefix,
        context);
    context.addDeps(
        foldersToInputsIndex.keySet(),
        targetNode.getDeps(),
        isTest ? IjModuleGraph.DependencyType.TEST : IjModuleGraph.DependencyType.PROD);
  }

  private static <T extends JavaLibraryDescription.Arg> void addCompiledShadowIfNeeded(
      TargetNode<T> targetNode,
      ModuleBuildContext context) {
    T arg = targetNode.getConstructorArg();
    // TODO(mkosiba): investigate supporting annotation processors without resorting to this.
    boolean hasAnnotationProcessors = !arg.annotationProcessors.get().isEmpty();
    if (containsNonSourcePath(arg.srcs) || hasAnnotationProcessors) {
      context.addCompileShadowDep(targetNode.getBuildTarget());
    }
  }

  private static class AndroidBinaryModuleRule
      implements IjModuleRule<AndroidBinaryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return AndroidBinaryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<AndroidBinaryDescription.Arg> target, ModuleBuildContext context) {
      context.addDeps(target.getDeps(), IjModuleGraph.DependencyType.PROD);

      AndroidBinaryDescription.Arg arg = target.getConstructorArg();
      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();
      // TODO(mkosiba): Add arg.keystore and arg.noDx.
      androidFacetBuilder
          .setManifestPath(arg.manifest)
          .setProguardConfigPath(arg.proguardConfig);
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
          false /* isTest */,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
      Optional<Path> dummyRDotJavaClassPath =
          dummyRDotJavaClassPathResolver.apply(target);
      if (dummyRDotJavaClassPath.isPresent()) {
        context.addExtraClassPathDependency(dummyRDotJavaClassPath.get());
      }
      context.ensureAndroidFacetBuilder();
    }
  }

  private static class AndroidResourceModuleRule
      implements IjModuleRule<AndroidResourceDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return AndroidResourceDescription.TYPE;
    }

    @Override
    public void apply(
        TargetNode<AndroidResourceDescription.Arg> target,
        ModuleBuildContext context) {
      context.addDeps(target.getDeps(), IjModuleGraph.DependencyType.PROD);

      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();
      AndroidResourceDescription.Arg arg = target.getConstructorArg();

      // TODO(mkosiba): Add support for arg.rDotJavaPackage and maybe arg.manifest
      if (arg.assets.isPresent()) {
        androidFacetBuilder.addAssetPaths(arg.assets.get());
      }
      if (arg.res.isPresent()) {
        androidFacetBuilder.addResourcePaths(arg.res.get());
      }
    }
  }

  private static class JavaLibraryModuleRule implements IjModuleRule<JavaLibraryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return JavaLibraryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<JavaLibraryDescription.Arg> target, ModuleBuildContext context) {
      addDepsAndSources(
          target,
          false /* isTest */,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
    }
  }

  private static class JavaTestModuleRule implements IjModuleRule<JavaTestDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return JavaTestDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<JavaTestDescription.Arg> target, ModuleBuildContext context) {
      addDepsAndSources(
          target,
          true /* isTest */,
          true /* wantsPackagePrefix */,
          context);
      addCompiledShadowIfNeeded(target, context);
    }
  }

  private static class RobolectricTestModuleRule extends JavaTestModuleRule {

    @Override
    public BuildRuleType getType() {
      return RobolectricTestDescription.TYPE;
    }
  }
}

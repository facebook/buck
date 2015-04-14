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
import com.facebook.buck.java.JavaBinaryDescription;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ProjectConfigDescription;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Builds {@link IjModule}s out of {@link TargetNode}s.
 */
public class IjModuleFactory {

  /**
   * This allows us to construct the module and it's android facet at the same time. Currently
   * this makes the code simpler than splitting it out ino two phases. Should the complexity of the
   * {@link IjModuleFactory.IjModuleRule} implementations increase we might want to re-visit the
   * decision.
   */
  private static class ModuleBuildContext {
    private final Path moduleBasePath;
    private final IjModule.Builder moduleBuilder;
    private Optional<IjModuleAndroidFacet.Builder> androidFacetBuilder;

    public ModuleBuildContext(
        Path moduleBasePath,
        IjModule.Builder moduleBuilder,
        Optional<IjModuleAndroidFacet.Builder> androidFacetBuilder) {
      this.moduleBasePath = moduleBasePath;
      this.moduleBuilder = moduleBuilder;
      this.androidFacetBuilder = androidFacetBuilder;
    }

    public Path getModuleBasePath() {
      return moduleBasePath;
    }

    public IjModule.Builder getModuleBuilder() {
      return moduleBuilder;
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

  /**
   * Only one of a single target type from this set can be represented in a single IntelliJ module
   * without substantial loss of information.
   */
  private static final ImmutableSet<BuildRuleType> MUTUALLY_EXCLUSIVE_TYPES = ImmutableSet.of(
      AndroidBinaryDescription.TYPE,
      JavaBinaryDescription.TYPE);

  private final Map<BuildRuleType, IjModuleRule<?>> moduleRuleIndex = new HashMap<>();
  private final IjLibraryFactory libraryFactory;

  public IjModuleFactory(IjLibraryFactory libraryFactory) {
    this.libraryFactory = libraryFactory;

    addToIndex(new AndroidBinaryModuleRule());
    addToIndex(new AndroidLibraryModuleRule());
    addToIndex(new AndroidResourceModuleRule());
    addToIndex(new JavaLibraryModuleRule());
    addToIndex(new JavaTestModuleRule());
    addToIndex(new ProjectConfigModuleRule());
  }

  private void addToIndex(IjModuleRule<?> rule) {
    Preconditions.checkArgument(!moduleRuleIndex.containsKey(rule.getType()));
    moduleRuleIndex.put(rule.getType(), rule);
  }

  public ImmutableSet<TargetNode<?>> getMutuallyExclusiveTargets(
      ImmutableSet<TargetNode<?>> input) {
    return FluentIterable.from(input)
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return MUTUALLY_EXCLUSIVE_TYPES.contains(input.getType());
              }
            })
        .toSet();
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
    final ImmutableSet<TargetNode<?>> mutuallyExclusiveTargets =
        getMutuallyExclusiveTargets(targetNodes);

    final ImmutableList<BuildTarget> targetsOverrides = FluentIterable.from(targetNodes)
        .firstMatch(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return input.getType() == ProjectConfigDescription.TYPE;
              }
            })
        .transform(
            new Function<TargetNode<?>, ImmutableList<BuildTarget>>() {
              @Nullable
              @Override
              public ImmutableList<BuildTarget> apply(TargetNode<?> input) {
                ImmutableList.Builder<BuildTarget> rootOverridesBuilder = ImmutableList.builder();
                ProjectConfigDescription.Arg arg =
                    (ProjectConfigDescription.Arg) input.getConstructorArg();
                if (arg.srcTarget.isPresent()) {
                  rootOverridesBuilder.add(arg.srcTarget.get());
                }
                if (arg.testTarget.isPresent()) {
                  rootOverridesBuilder.add(arg.testTarget.get());
                }
                return rootOverridesBuilder.build();
              }
            })
        .or(ImmutableList.<BuildTarget>of());

    // This is a bit shady. We're essentially using the project_config to pick one out of many
    // conflicting targets (like multiple android_binary targets) in a BUCK file.
    // We should probably have two modes for project generation:
    //  - edit-only, where we treat everything as a nail (well, Java library target) the purpose
    //    of which would be to make it possible for IntelliJ to index the code, but not build it,
    //  - strict, where we attempt a 1:1 mapping between buck and IntelliJ. In this case any buck
    //    features which we can't map to IntelliJ would cause errors.
    if (mutuallyExclusiveTargets.size() > 1 && !targetsOverrides.isEmpty()) {
      targetNodes = FluentIterable.from(targetNodes)
          .filter(
              new Predicate<TargetNode<?>>() {
                @Override
                public boolean apply(TargetNode<?> input) {
                  return !mutuallyExclusiveTargets.contains(input) ||
                      targetsOverrides.contains(input.getBuildTarget());
                }
              })
          .toSet();
    }

    Preconditions.checkArgument(getMutuallyExclusiveTargets(targetNodes).size() <= 1);

    ModuleBuildContext context = new ModuleBuildContext(
        moduleBasePath,
        IjModule.builder(),
        Optional.<IjModuleAndroidFacet.Builder>absent());

    for (TargetNode<?> targetNode : targetNodes) {
      IjModuleRule<?> rule = moduleRuleIndex.get(targetNode.getType());
      if (rule == null) {
        continue;
      }

      rule.apply((TargetNode) targetNode, context);
    }

    Optional<IjModuleAndroidFacet> androidFacetOptional = context.androidFacetBuilder
        .transform(
            new Function<IjModuleAndroidFacet.Builder, IjModuleAndroidFacet>() {
              @Override
              public IjModuleAndroidFacet apply(IjModuleAndroidFacet.Builder input) {
                return input.build();
              }
            });

    return context.moduleBuilder
        .setModuleBasePath(moduleBasePath)
        .setTargets(targetNodes)
        .addAllLibraries(libraryFactory.getLibraries(targetNodes))
        .setAndroidFacet(androidFacetOptional)
        .build();
  }

  /**
   * Add the set of input paths to the {@link IjModule.Builder} as source folders.
   *
   * @param target targets whose inputs to convert to source folders.
   * @param type folder type.
   * @param wantsPackagePrefix whether folders should be annotated with a package prefix. This
   *                           only makes sense when the source folder is Java source code.
   * @param context the module to add the folders to.
   */
  private static void addSourceFolders(
      TargetNode<?> target,
      IjFolder.Type type,
      boolean wantsPackagePrefix,
      ModuleBuildContext context) {
    ImmutableSet<Path> sourceFolders = FluentIterable.from(target.getInputs())
        .transform(
            new Function<Path, Path>() {
              @Override
              public Path apply(Path input) {
                Path parent = input.getParent();
                if (parent == null) {
                  return Paths.get("");
                }
                return parent;
              }
            })
        .toSet();

    for (Path path : sourceFolders) {
      Path moduleRelativePath = context.getModuleBasePath().relativize(path);
      context.getModuleBuilder().addInferredFolders(
          IjFolder.builder()
              .setModuleRelativePath(moduleRelativePath)
              .setType(type)
              .setWantsPackagePrefix(wantsPackagePrefix)
              .build());
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
      AndroidBinaryDescription.Arg arg = target.getConstructorArg();
      IjModuleAndroidFacet.Builder androidFacetBuilder = context.getOrCreateAndroidFacetBuilder();

      // TODO(mkosiba): Add arg.keystore and arg.noDx.
      androidFacetBuilder
          .setManifestPath(arg.manifest)
          .setProguardConfigPath(arg.proguardConfig);
    }
  }

  private static class AndroidLibraryModuleRule
      implements IjModuleRule<AndroidLibraryDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return AndroidLibraryDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<AndroidLibraryDescription.Arg> target,
        ModuleBuildContext context) {
      addSourceFolders(
          target,
          IjFolder.Type.SOURCE_FOLDER,
          true /* wantsPackagePrefix */,
          context);
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
      addSourceFolders(
          target,
          IjFolder.Type.SOURCE_FOLDER,
          true /* wantsPackagePrefix */,
          context);
    }
  }

  private static class JavaTestModuleRule implements IjModuleRule<JavaTestDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return JavaTestDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<JavaTestDescription.Arg> target, ModuleBuildContext context) {
      addSourceFolders(
          target,
          IjFolder.Type.TEST_FOLDER,
          true /* wantsPackagePrefix */,
          context);
    }
  }

  private static class ProjectConfigModuleRule
      implements IjModuleRule<ProjectConfigDescription.Arg> {

    @Override
    public BuildRuleType getType() {
      return ProjectConfigDescription.TYPE;
    }

    @Override
    public void apply(TargetNode<ProjectConfigDescription.Arg> target, ModuleBuildContext context) {
      ProjectConfigDescription.Arg arg = target.getConstructorArg();

      // This was only ever added to support cases where we can't guess the package for the input
      // files properly (like for "third-party/java/lib/1.0.0/org/someone/lib").
      for (String root : (arg.srcRoots.or(ImmutableList.<String>of()))) {
        context.getModuleBuilder().addFolderOverride(
            IjFolder.builder()
                .setModuleRelativePath(Paths.get(root))
                .setType(IjFolder.Type.SOURCE_FOLDER)
                .setWantsPackagePrefix(false)
                .build());
      }

      for (String root : (arg.testRoots.or(ImmutableList.<String>of()))) {
        context.getModuleBuilder().addFolderOverride(
            IjFolder.builder()
                .setModuleRelativePath(Paths.get(root))
                .setType(IjFolder.Type.TEST_FOLDER)
                .setWantsPackagePrefix(false)
                .build());
      }
    }
  }
}

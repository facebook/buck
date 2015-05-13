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
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

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
    private Optional<IjModuleAndroidFacet.Builder> androidFacetBuilder;
    private final Map<Path, IjFolder> sourceFoldersMergeMap;

    public ModuleBuildContext(Path moduleBasePath) {
      this.moduleBasePath = moduleBasePath;
      this.androidFacetBuilder = Optional.absent();
      this.sourceFoldersMergeMap = new HashMap<>();
    }

    public Path getModuleBasePath() {
      return moduleBasePath;
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

    public ImmutableSet<IjFolder> getSourceFolders() {
      return ImmutableSet.copyOf(sourceFoldersMergeMap.values());
    }

    public void addSourceFolder(IjFolder folder) {
      Path path = folder.getPath();
      if (!sourceFoldersMergeMap.containsKey(path)) {
        sourceFoldersMergeMap.put(path, folder);
        return;
      }
      IjFolder otherFolder = sourceFoldersMergeMap.get(path);
      IjFolder merged = folder.merge(otherFolder);
      sourceFoldersMergeMap.put(path, merged);
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
  private final IjLibraryFactory libraryFactory;

  public IjModuleFactory(IjLibraryFactory libraryFactory) {
    this.libraryFactory = libraryFactory;

    addToIndex(new AndroidBinaryModuleRule());
    addToIndex(new AndroidLibraryModuleRule());
    addToIndex(new AndroidResourceModuleRule());
    addToIndex(new JavaLibraryModuleRule());
    addToIndex(new JavaTestModuleRule());
  }

  private void addToIndex(IjModuleRule<?> rule) {
    Preconditions.checkArgument(!moduleRuleIndex.containsKey(rule.getType()));
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

    ModuleBuildContext context = new ModuleBuildContext(moduleBasePath);

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

    return IjModule.builder()
        .setModuleBasePath(moduleBasePath)
        .setTargets(targetNodes)
        .addAllLibraries(libraryFactory.getLibraries(targetNodes))
        .addAllFolders(context.getSourceFolders())
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
      Preconditions.checkArgument(path.startsWith(context.getModuleBasePath()));
      context.addSourceFolder(
          IjFolder.builder()
              .setPath(path)
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
}

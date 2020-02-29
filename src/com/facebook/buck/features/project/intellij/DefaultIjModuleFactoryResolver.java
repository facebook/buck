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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidBinaryDescriptionArg;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidLibraryGraphEnhancer;
import com.facebook.buck.android.AndroidPrebuiltAarDescriptionArg;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.android.DummyRDotJava;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.kotlin.KotlincToJarStepFactory;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

class DefaultIjModuleFactoryResolver implements IjModuleFactoryResolver {

  private final SourcePathResolverAdapter sourcePathResolver;
  private final ProjectFilesystem projectFilesystem;
  private final Optional<Set<BuildTarget>> requiredBuildTargets;
  private TargetGraph targetGraph;

  DefaultIjModuleFactoryResolver(
      IjProjectSourcePathResolver sourcePathResolver,
      ProjectFilesystem projectFilesystem,
      Optional<Set<BuildTarget>> requiredBuildTargets,
      TargetGraph targetGraph) {
    this.sourcePathResolver = new SourcePathResolverAdapter(sourcePathResolver);
    this.projectFilesystem = projectFilesystem;
    this.requiredBuildTargets = requiredBuildTargets;
    this.targetGraph = targetGraph;
  }

  @Override
  public Optional<Path> getDummyRDotJavaPath(TargetNode<?> targetNode) {
    BuildTarget dummyRDotJavaTarget =
        AndroidLibraryGraphEnhancer.getDummyRDotJavaTarget(targetNode.getBuildTarget());
    if (willHaveDummyRDotJavaRule(targetNode)) {
      requiredBuildTargets.ifPresent(requiredTargets -> requiredTargets.add(dummyRDotJavaTarget));
      return Optional.of(DummyRDotJava.getOutputJarPath(dummyRDotJavaTarget, projectFilesystem));
    }
    return Optional.empty();
  }

  private boolean willHaveDummyRDotJavaRule(TargetNode<?> targetNode) {
    return targetNode.getBuildDeps().stream()
        .anyMatch(
            dep -> {
              ConstructorArg constructorArg = targetGraph.get(dep).getConstructorArg();
              if (constructorArg instanceof AndroidResourceDescriptionArg) {
                // AndroidResource implements HasAndroidResourceDeps
                return true;
              } else if (constructorArg instanceof AndroidPrebuiltAarDescriptionArg) {
                // AndroidPrebuiltAar implements HasAndroidResourceDeps
                return true;
              }
              return false;
            });
  }

  @Override
  public Path getAndroidManifestPath(TargetNode<AndroidBinaryDescriptionArg> targetNode) {
    AndroidBinaryDescriptionArg arg = targetNode.getConstructorArg();
    Optional<SourcePath> manifestSourcePath = arg.getManifest();
    if (!manifestSourcePath.isPresent()) {
      manifestSourcePath = arg.getManifestSkeleton();
    }
    if (!manifestSourcePath.isPresent()) {
      throw new IllegalArgumentException(
          "android_binary "
              + targetNode.getBuildTarget()
              + " did not specify manifest or manifest_skeleton");
    }
    return sourcePathResolver.getAbsolutePath(manifestSourcePath.get());
  }

  @Override
  public Optional<Path> getLibraryAndroidManifestPath(
      TargetNode<AndroidLibraryDescription.CoreArg> targetNode) {
    Optional<SourcePath> manifestPath = targetNode.getConstructorArg().getManifest();
    return manifestPath
        .map(sourcePathResolver::getAbsolutePath)
        .map(path -> projectFilesystem.relativize(path).getPath());
  }

  @Override
  public Optional<Path> getProguardConfigPath(TargetNode<AndroidBinaryDescriptionArg> targetNode) {
    return targetNode
        .getConstructorArg()
        .getProguardConfig()
        .map(this::getRelativePathAndRecordRule);
  }

  @Override
  public Optional<Path> getAndroidResourcePath(
      TargetNode<AndroidResourceDescriptionArg> targetNode) {
    AndroidResourceDescriptionArg arg = targetNode.getConstructorArg();
    if (arg.getProjectRes().isPresent()) {
      return arg.getProjectRes();
    }
    if (!arg.getRes().isPresent()) {
      return Optional.empty();
    }
    if (arg.getRes().get().isLeft()) {
      // Left is a simple source path
      return Optional.of(
          sourcePathResolver.getRelativePath(
              targetNode.getFilesystem(), arg.getRes().get().getLeft()));
    } else {
      // Right is a mapped set of paths, so we need the symlink tree
      return Optional.of(
          sourcePathResolver.getRelativePath(
              targetNode.getFilesystem(),
              DefaultBuildTargetSourcePath.of(
                  targetNode
                      .getBuildTarget()
                      .withAppendedFlavors(
                          AndroidResourceDescription.RESOURCES_SYMLINK_TREE_FLAVOR))));
    }
  }

  @Override
  public Optional<Path> getAssetsPath(TargetNode<AndroidResourceDescriptionArg> targetNode) {
    AndroidResourceDescriptionArg arg = targetNode.getConstructorArg();
    if (arg.getProjectAssets().isPresent()) {
      return arg.getProjectAssets();
    }
    if (!arg.getAssets().isPresent()) {
      return Optional.empty();
    }
    if (arg.getAssets().get().isLeft()) {
      // Left is a simple source path
      return Optional.of(sourcePathResolver.getRelativePath(arg.getAssets().get().getLeft()));
    } else {
      // Right is a mapped set of paths, so we need the symlink tree
      return Optional.of(
          sourcePathResolver.getRelativePath(
              targetNode.getFilesystem(),
              DefaultBuildTargetSourcePath.of(
                  targetNode
                      .getBuildTarget()
                      .withAppendedFlavors(
                          AndroidResourceDescription.ASSETS_SYMLINK_TREE_FLAVOR))));
    }
  }

  @Override
  public Optional<Path> getAnnotationOutputPath(TargetNode<? extends JvmLibraryArg> targetNode) {
    JvmLibraryArg constructorArg = targetNode.getConstructorArg();
    if (constructorArg.getPlugins().isEmpty()
        && constructorArg.getAnnotationProcessors().isEmpty()) {
      return Optional.empty();
    }
    return CompilerOutputPaths.getAnnotationPath(projectFilesystem, targetNode.getBuildTarget());
  }

  @Override
  public Optional<Path> getKaptAnnotationOutputPath(
      TargetNode<? extends JvmLibraryArg> targetNode) {
    if (IjKotlinHelper.isKotlinModule(targetNode.getConstructorArg())
        && IjKotlinHelper.requiresKapt(targetNode.getConstructorArg())) {
      return Optional.of(
          KotlincToJarStepFactory.getKaptAnnotationGenPath(
              projectFilesystem, targetNode.getBuildTarget()));
    }
    return Optional.empty();
  }

  @Override
  public Optional<Path> getCompilerOutputPath(TargetNode<? extends JvmLibraryArg> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();

    // TODO(nga): when resolving target path, we use wrong cell filesystem:
    //    either explain why we do it, or fix it.
    BuildTarget buildTargetPatchedCell =
        buildTargetPatchCell(buildTarget, projectFilesystem.getBuckPaths().getCellName());

    Path compilerOutputPath =
        CompilerOutputPaths.getOutputJarPath(buildTargetPatchedCell, projectFilesystem);
    return Optional.of(compilerOutputPath);
  }

  private static BuildTarget buildTargetPatchCell(
      BuildTarget buildTarget, CanonicalCellName cellName) {
    return UnconfiguredBuildTarget.of(
            UnflavoredBuildTarget.of(
                CellRelativePath.of(cellName, buildTarget.getCellRelativeBasePath().getPath()),
                buildTarget.getShortName()),
            buildTarget.getFlavors())
        .configure(buildTarget.getTargetConfiguration());
  }

  private Path getRelativePathAndRecordRule(SourcePath sourcePath) {
    SourcePathResolver resolver = sourcePathResolver.getResolver();
    Preconditions.checkState(
        resolver instanceof IjProjectSourcePathResolver,
        "Expected IjProjectSourcePathResolver, got %s",
        resolver.getClass().getName());
    requiredBuildTargets.ifPresent(
        requiredTargets ->
            ((IjProjectSourcePathResolver) resolver)
                .getBuildTarget(sourcePath)
                .ifPresent(requiredTargets::add));
    return sourcePathResolver.getRelativePath(sourcePath);
  }
}

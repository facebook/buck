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
package com.facebook.buck.ide.intellij;

import com.facebook.buck.android.AndroidBinaryDescriptionArg;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidLibraryGraphEnhancer;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.android.DummyRDotJava;
import com.facebook.buck.ide.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.CompilerParameters;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.Optionals;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

class DefaultIjModuleFactoryResolver implements IjModuleFactoryResolver {

  private final BuildRuleResolver buildRuleResolver;
  private final SourcePathResolver sourcePathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ProjectFilesystem projectFilesystem;
  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargets;

  DefaultIjModuleFactoryResolver(
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargets) {
    this.buildRuleResolver = buildRuleResolver;
    this.sourcePathResolver = sourcePathResolver;
    this.ruleFinder = ruleFinder;
    this.projectFilesystem = projectFilesystem;
    this.requiredBuildTargets = requiredBuildTargets;
  }

  @Override
  public Optional<Path> getDummyRDotJavaPath(TargetNode<?, ?> targetNode) {
    BuildTarget dummyRDotJavaTarget =
        AndroidLibraryGraphEnhancer.getDummyRDotJavaTarget(targetNode.getBuildTarget());
    Optional<BuildRule> dummyRDotJavaRule = buildRuleResolver.getRuleOptional(dummyRDotJavaTarget);
    if (dummyRDotJavaRule.isPresent()) {
      requiredBuildTargets.add(dummyRDotJavaTarget);
      return Optional.of(
          DummyRDotJava.getRDotJavaBinFolder(dummyRDotJavaTarget, projectFilesystem));
    }
    return Optional.empty();
  }

  @Override
  public Path getAndroidManifestPath(TargetNode<AndroidBinaryDescriptionArg, ?> targetNode) {
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
      TargetNode<AndroidLibraryDescription.CoreArg, ?> targetNode) {
    Optional<SourcePath> manifestPath = targetNode.getConstructorArg().getManifest();
    return manifestPath.map(sourcePathResolver::getAbsolutePath).map(projectFilesystem::relativize);
  }

  @Override
  public Optional<Path> getProguardConfigPath(
      TargetNode<AndroidBinaryDescriptionArg, ?> targetNode) {
    return targetNode
        .getConstructorArg()
        .getProguardConfig()
        .map(this::getRelativePathAndRecordRule);
  }

  @Override
  public Optional<Path> getAndroidResourcePath(
      TargetNode<AndroidResourceDescriptionArg, ?> targetNode) {
    return AndroidResourceDescription.getResDirectoryForProject(buildRuleResolver, targetNode)
        .map(this::getRelativePathAndRecordRule);
  }

  @Override
  public Optional<Path> getAssetsPath(TargetNode<AndroidResourceDescriptionArg, ?> targetNode) {
    return AndroidResourceDescription.getAssetsDirectoryForProject(buildRuleResolver, targetNode)
        .map(this::getRelativePathAndRecordRule);
  }

  @Override
  public Optional<Path> getAnnotationOutputPath(TargetNode<? extends JvmLibraryArg, ?> targetNode) {
    AnnotationProcessingParams annotationProcessingParams =
        targetNode
            .getConstructorArg()
            .buildAnnotationProcessingParams(
                targetNode.getBuildTarget(),
                projectFilesystem,
                buildRuleResolver,
                ImmutableSet.of());
    if (annotationProcessingParams == null || annotationProcessingParams.isEmpty()) {
      return Optional.empty();
    }

    return CompilerParameters.getAnnotationPath(projectFilesystem, targetNode.getBuildTarget());
  }

  @Override
  public Optional<Path> getCompilerOutputPath(TargetNode<? extends JvmLibraryArg, ?> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Path compilerOutputPath = DefaultJavaLibrary.getOutputJarPath(buildTarget, projectFilesystem);
    return Optional.of(compilerOutputPath);
  }

  private Path getRelativePathAndRecordRule(SourcePath sourcePath) {
    requiredBuildTargets.addAll(
        Optionals.toStream(ruleFinder.getRule(sourcePath).map(BuildRule::getBuildTarget))
            .iterator());
    return sourcePathResolver.getRelativePath(sourcePath);
  }
}

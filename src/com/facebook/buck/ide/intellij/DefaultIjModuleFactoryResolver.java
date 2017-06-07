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
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.OptionalCompat;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

class DefaultIjModuleFactoryResolver implements IjModuleFactoryResolver {

  private final BuildRuleResolver buildRuleResolver;
  private final SourcePathResolver sourcePathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ProjectFilesystem projectFilesystem;
  private final IjProjectConfig projectConfig;
  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargets;

  DefaultIjModuleFactoryResolver(
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      IjProjectConfig projectConfig,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargets) {
    this.buildRuleResolver = buildRuleResolver;
    this.sourcePathResolver = sourcePathResolver;
    this.ruleFinder = ruleFinder;
    this.projectFilesystem = projectFilesystem;
    this.projectConfig = projectConfig;
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
    return sourcePathResolver.getAbsolutePath(targetNode.getConstructorArg().getManifest());
  }

  @Override
  public Optional<Path> getLibraryAndroidManifestPath(
      TargetNode<AndroidLibraryDescription.CoreArg, ?> targetNode) {
    Optional<SourcePath> manifestPath = targetNode.getConstructorArg().getManifest();
    Optional<Path> defaultAndroidManifestPath =
        projectConfig.getAndroidManifest().map(Path::toAbsolutePath);
    return manifestPath
        .map(sourcePathResolver::getAbsolutePath)
        .map(Optional::of)
        .orElse(defaultAndroidManifestPath);
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

    return Optional.ofNullable(annotationProcessingParams.getGeneratedSourceFolderName());
  }

  private Path getRelativePathAndRecordRule(SourcePath sourcePath) {
    requiredBuildTargets.addAll(
        OptionalCompat.asSet(ruleFinder.getRule(sourcePath).map(BuildRule::getBuildTarget)));
    return sourcePathResolver.getRelativePath(sourcePath);
  }
}

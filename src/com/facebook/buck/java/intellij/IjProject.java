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

import com.facebook.buck.android.AndroidLibraryGraphEnhancer;
import com.facebook.buck.android.DummyRDotJava;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaFileParser;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Top-level class for IntelliJ project generation.
 */
public class IjProject {

  private final TargetGraphAndTargets targetGraphAndTargets;
  private final JavaPackageFinder javaPackageFinder;
  private final JavaFileParser javaFileParser;
  private final BuildRuleResolver buildRuleResolver;
  private final SourcePathResolver sourcePathResolver;
  private final ProjectFilesystem projectFilesystem;
  private final IjModuleGraph.AggregationMode aggregationMode;

  public IjProject(
      TargetGraphAndTargets targetGraphAndTargets,
      JavaPackageFinder javaPackageFinder,
      JavaFileParser javaFileParser,
      BuildRuleResolver buildRuleResolver,
      SourcePathResolver sourcePathResolver,
      ProjectFilesystem projectFilesystem,
      IjModuleGraph.AggregationMode aggregationMode) {
    this.targetGraphAndTargets = targetGraphAndTargets;
    this.javaPackageFinder = javaPackageFinder;
    this.javaFileParser = javaFileParser;
    this.buildRuleResolver = buildRuleResolver;
    this.sourcePathResolver = sourcePathResolver;
    this.projectFilesystem = projectFilesystem;
    this.aggregationMode = aggregationMode;
  }

  /**
   * Write the project to disk.
   *
   * @return set of {@link BuildTarget}s which should be built in order for the project to index
   *   correctly.
   * @throws IOException
   */
  public ImmutableSet<BuildTarget> write() throws IOException {
    final ImmutableSet.Builder<BuildTarget> requiredBuildTargets = ImmutableSet.builder();
    IjLibraryFactory libraryFactory = new DefaultIjLibraryFactory(
        new DefaultIjLibraryFactory.IjLibraryFactoryResolver() {
          @Override
          public Path getPath(SourcePath path) {
            Optional<BuildRule> rule = sourcePathResolver.getRule(path);
            if (rule.isPresent()) {
              requiredBuildTargets.add(rule.get().getBuildTarget());
            }
            return sourcePathResolver.getPath(path);
          }

          @Override
          public Optional<Path> getPathIfJavaLibrary(TargetNode<?> targetNode) {
            BuildRule rule = buildRuleResolver.getRule(targetNode.getBuildTarget());
            if (!(rule instanceof JavaLibrary)) {
              return Optional.absent();
            }
            requiredBuildTargets.add(rule.getBuildTarget());
            return Optional.fromNullable(rule.getPathToOutput());
          }
        });
    IjModuleFactory moduleFactory = new IjModuleFactory(
        new Function<TargetNode<?>, Optional<Path>>() {
          @Override
          public Optional<Path> apply(TargetNode<?> targetNode) {
            BuildTarget dummyRDotJavaTarget = AndroidLibraryGraphEnhancer.getDummyRDotJavaTarget(
                targetNode.getBuildTarget());
            Optional<BuildRule> dummyRDotJavaRule =
                buildRuleResolver.getRuleOptional(dummyRDotJavaTarget);
            if (dummyRDotJavaRule.isPresent()) {
              requiredBuildTargets.add(dummyRDotJavaTarget);
              return Optional.of(DummyRDotJava.getRDotJavaBinFolder(dummyRDotJavaTarget));
            }
            return Optional.absent();
          }
        });
    IjModuleGraph moduleGraph = IjModuleGraph.from(
        targetGraphAndTargets.getTargetGraph(),
        libraryFactory,
        moduleFactory,
        aggregationMode);
    JavaPackageFinder parsingJavaPackageFinder = ParsingJavaPackageFinder.preparse(
        javaFileParser,
        projectFilesystem,
        IjProjectTemplateDataPreparer.createPackageLookupPathSet(moduleGraph),
        javaPackageFinder);
    IjProjectWriter writer = new IjProjectWriter(
        new IjProjectTemplateDataPreparer(parsingJavaPackageFinder, moduleGraph, projectFilesystem),
        projectFilesystem);
    writer.write();
    return requiredBuildTargets.build();
  }

}

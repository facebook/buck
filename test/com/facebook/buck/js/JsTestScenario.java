/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.FakeWorkerBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

public class JsTestScenario {
  final TargetGraph targetGraph;
  final BuildRuleResolver resolver;
  final BuildTarget workerTarget;
  final ProjectFilesystem filesystem;

  static Builder builder() {
    return new Builder();
  }

  private JsTestScenario(
      TargetGraph targetGraph,
      BuildRuleResolver resolver,
      BuildTarget workerTarget,
      ProjectFilesystem filesystem) {
    this.targetGraph = targetGraph;
    this.resolver = resolver;
    this.workerTarget = workerTarget;
    this.filesystem = filesystem;
  }

  JsBundle createBundle(
      String target,
      ImmutableSortedSet<BuildTarget> libs) throws NoSuchBuildTargetException {
    return createBundle(target, libs, Either.ofLeft(ImmutableSet.of()));
  }

  private JsBundle createBundle(
      String target,
      ImmutableSortedSet<BuildTarget> libs,
      Either<ImmutableSet<String>, String> entry) throws NoSuchBuildTargetException {
    return new JsBundleBuilder(
        BuildTargetFactory.newInstance(target),
        workerTarget,
        libs,
        entry,
        filesystem
    ).build(resolver, targetGraph);
  }

  static class Builder {
    private final Set<TargetNode<?, ?>> nodes = new LinkedHashSet<>();
    private final BuildTarget workerTarget = BuildTargetFactory.newInstance("//worker:tool");
    private final ProjectFilesystem filesystem = new FakeProjectFilesystem();

    private Builder() {
      nodes.add(new FakeWorkerBuilder(workerTarget).build());
    }

    Builder bundle(BuildTarget target, BuildTarget... libraryDependencies) {
      final Either<ImmutableSet<String>, String> entry = Either.ofLeft(ImmutableSet.of());
      final ImmutableSortedSet<BuildTarget> libs = ImmutableSortedSet.copyOf(libraryDependencies);
      nodes.add(new JsBundleBuilder(target, workerTarget, libs, entry, filesystem).build());
      return this;
    }

    Builder library(BuildTarget target, BuildTarget... libraryDependencies) {
      nodes.add(
          new JsLibraryBuilder(target, workerTarget, filesystem)
              .setLibs(ImmutableSortedSet.copyOf(libraryDependencies))
              .build());
      return this;
    }

    Builder library(BuildTarget target, String basePath, SourcePath... sources) {
      addLibrary(
          target,
          basePath,
          Stream.of(sources).map(Either::ofLeft));
      return this;
    }

    Builder library(
        BuildTarget target,
        String basePath,
        Pair<SourcePath, String> source) {
      addLibrary(target, basePath, Stream.of(source).map(Either::ofRight));
      return this;
    }

    private void addLibrary(
        BuildTarget target,
        String basePath,
        Stream<Either<SourcePath, Pair<SourcePath, String>>> sources) {
      nodes.add(
          new JsLibraryBuilder(target, workerTarget, filesystem)
              .setBasePath(basePath)
              .setSrcs(sources.collect(MoreCollectors.toImmutableSet()))
              .build());
    }

    Builder arbitraryRule(BuildTarget target) {
      nodes.add(ExportFileBuilder.newExportFileBuilder(target).build());
      return this;
    }

    JsTestScenario build() throws NoSuchBuildTargetException {
      final TargetGraph graph = TargetGraphFactory.newInstance(nodes);
      final BuildRuleResolver resolver = new BuildRuleResolver(
          graph,
          new DefaultTargetNodeToBuildRuleTransformer());
      for (TargetNode<?, ?> node : nodes) {
        resolver.requireRule(node.getBuildTarget());
      }
      return new JsTestScenario(
          graph,
          resolver,
          workerTarget,
          filesystem);
    }
  }
}

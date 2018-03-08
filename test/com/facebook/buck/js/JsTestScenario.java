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

import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.FakeWorkerBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class JsTestScenario {
  public final TargetGraph targetGraph;
  public final BuildRuleResolver resolver;
  public final BuildTarget workerTarget;
  public final ProjectFilesystem filesystem;

  public static Builder builder() {
    return new Builder();
  }

  static Builder builder(JsTestScenario other) {
    return new Builder(other);
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

  JsBundle createBundle(String target, ImmutableSortedSet<BuildTarget> deps)
      throws NoSuchBuildTargetException {
    return createBundle(target, builder -> builder.setDeps(deps));
  }

  JsBundle createBundle(String target, Function<JsBundleBuilder, JsBundleBuilder> setUp)
      throws NoSuchBuildTargetException {
    return createBundle(target, Either.ofLeft(ImmutableSet.of()), setUp);
  }

  private JsBundle createBundle(
      String target,
      Either<ImmutableSet<String>, String> entry,
      Function<JsBundleBuilder, JsBundleBuilder> setUp)
      throws NoSuchBuildTargetException {
    return setUp
        .apply(
            new JsBundleBuilder(
                BuildTargetFactory.newInstance(target), workerTarget, entry, filesystem))
        .build(resolver, targetGraph);
  }

  public static class Builder {
    private final Set<TargetNode<?, ?>> nodes = new LinkedHashSet<>();
    private final BuildTarget workerTarget;
    private final ProjectFilesystem filesystem;

    private Builder() {
      workerTarget = BuildTargetFactory.newInstance("//worker:tool");
      nodes.add(new FakeWorkerBuilder(workerTarget).build());
      filesystem = new FakeProjectFilesystem();
    }

    private Builder(JsTestScenario other) {
      nodes.addAll(other.targetGraph.getNodes());
      workerTarget = other.workerTarget;
      filesystem = other.filesystem;
    }

    public Builder bundleWithDeps(BuildTarget target, BuildTarget... dependencies) {
      return bundle(target, ImmutableSortedSet.copyOf(dependencies));
    }

    public Builder bundle(BuildTarget target, ImmutableSortedSet<BuildTarget> deps) {
      Either<ImmutableSet<String>, String> entry = Either.ofLeft(ImmutableSet.of());
      nodes.add(new JsBundleBuilder(target, workerTarget, entry, filesystem).setDeps(deps).build());

      return this;
    }

    public Builder library(BuildTarget target, BuildTarget... libraryDependencies) {
      nodes.add(
          new JsLibraryBuilder(target, filesystem)
              .setDeps(ImmutableSortedSet.copyOf(libraryDependencies))
              .setWorker(workerTarget)
              .build());
      return this;
    }

    public Builder library(
        BuildTarget target, Query libraryDependenciesQuery, BuildTarget... libraryDependencies) {
      nodes.add(
          new JsLibraryBuilder(target, filesystem)
              .setDepsQuery(libraryDependenciesQuery)
              .setDeps(ImmutableSortedSet.copyOf(libraryDependencies))
              .setWorker(workerTarget)
              .build());
      return this;
    }

    public Builder library(BuildTarget target, SourcePath first, SourcePath... sources) {
      addLibrary(
          target, null, Stream.concat(Stream.of(first), Stream.of(sources)).map(Either::ofLeft));
      return this;
    }

    public Builder library(BuildTarget target, String basePath, SourcePath... sources) {
      addLibrary(target, basePath, Stream.of(sources).map(Either::ofLeft));
      return this;
    }

    public Builder library(BuildTarget target, String basePath, Pair<SourcePath, String> source) {
      addLibrary(target, basePath, Stream.of(source).map(Either::ofRight));
      return this;
    }

    public Builder bundleGenrule(JsBundleGenruleBuilder.Options options) {
      nodes.add(new JsBundleGenruleBuilder(options, filesystem).build());
      return this;
    }

    private void addLibrary(
        BuildTarget target,
        @Nullable String basePath,
        Stream<Either<SourcePath, Pair<SourcePath, String>>> sources) {
      nodes.add(
          new JsLibraryBuilder(target, filesystem)
              .setBasePath(basePath)
              .setSrcs(sources.collect(ImmutableSet.toImmutableSet()))
              .setWorker(workerTarget)
              .build());
    }

    public Builder arbitraryRule(BuildTarget target) {
      nodes.add(new ExportFileBuilder(target).build());
      return this;
    }

    public Builder appleLibraryWithDeps(BuildTarget target, BuildTarget... deps) {
      nodes.add(
          AppleLibraryBuilder.createBuilder(target)
              .setDeps(ImmutableSortedSet.copyOf(deps))
              .build());
      return this;
    }

    public JsTestScenario build() {
      TargetGraph graph = TargetGraphFactory.newInstance(nodes);
      BuildRuleResolver resolver = new TestBuildRuleResolver(graph);
      for (TargetNode<?, ?> node : nodes) {
        resolver.requireRule(node.getBuildTarget());
      }
      return new JsTestScenario(graph, resolver, workerTarget, filesystem);
    }
  }
}

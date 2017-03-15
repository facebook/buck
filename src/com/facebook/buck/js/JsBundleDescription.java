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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public class JsBundleDescription implements Description<JsBundleDescription.Arg>, Flavored {

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return JsFlavors.validateFlavors(flavors);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    // Flavors are propagated from js_bundle targets to their js_library dependencies
    // for that reason, dependencies of libraries are handled manually, and as a first step,
    // all dependencies to libraries are removed
    params = JsUtil.withWorkerDependencyOnly(params, resolver, args.worker);

    final Either<ImmutableSet<String>, String> entryPoint = args.entry;
    ImmutableSortedSet<BuildRule> libraryDeps =
        new TransitiveLibraryDependencies(params.getBuildTarget(), targetGraph, resolver)
            .collect(args.libs);

    return new JsBundle(
        params.appendExtraDeps(libraryDeps),
        libraryDeps.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet()),
        entryPoint.isLeft() ? entryPoint.getLeft() : ImmutableSet.of(entryPoint.getRight()),
        args.bundleName.orElse(params.getBuildTarget().getShortName() + ".js"),
        resolver.getRuleWithType(args.worker, WorkerTool.class));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<BuildTarget> libs;
    public Either<ImmutableSet<String>, String> entry;
    public Optional<String> bundleName;
    public BuildTarget worker;
  }

  private static class TransitiveLibraryDependencies {
    private final BuildTarget bundleTarget;
    private final ImmutableSortedSet<Flavor> extraFlavors;
    private final BuildRuleResolver resolver;
    private final SourcePathRuleFinder ruleFinder;
    private final TargetGraph targetGraph;

    private TransitiveLibraryDependencies(
        BuildTarget bundleTarget,
        TargetGraph targetGraph,
        BuildRuleResolver resolver
    ) {
      this.bundleTarget = bundleTarget;
      this.targetGraph = targetGraph;
      this.resolver = resolver;
      extraFlavors = bundleTarget.getFlavors();
      ruleFinder = new SourcePathRuleFinder(resolver);
    }

    private ImmutableSortedSet<BuildRule> collect(Collection<BuildTarget> firstLevelDeps) {
      Stream<BuildRule> deps = transitiveDependenciesForAll(
          firstLevelDeps.stream().map(this::requireRule),
          bundleTarget);
      return deps.collect(MoreCollectors.toImmutableSortedSet());
    }

    private Stream<BuildRule> transitiveDependenciesForAll(
        Stream<BuildRule> libraries,
        BuildTarget parent) {
      return libraries
          .map(rule -> JsUtil.verifyIsJsLibrary(rule, parent, targetGraph))
          .map(this::transitiveDependenciesFor)
          .flatMap(Function.identity());
    }

    private Stream<BuildRule> transitiveDependenciesFor(JsLibrary library) {
      return Stream.concat(
          Stream.of(library),
          transitiveDependenciesForAll(
              library.getLibraryDependencies(ruleFinder)
                  .map(t -> resolver.getRule(t.withAppendedFlavors(extraFlavors))),
              library.getBuildTarget()));
    }

    private BuildRule requireRule(BuildTarget target) {
      try {
        return resolver.requireRule(target.withAppendedFlavors(extraFlavors));
      } catch (NoSuchBuildTargetException e) {
        throw new HumanReadableException(e);
      }
    }
  }
}

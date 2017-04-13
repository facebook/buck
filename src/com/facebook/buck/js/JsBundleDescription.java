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

import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.Hint;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public class JsBundleDescription implements Description<JsBundleDescription.Arg>, Flavored {

  private static final ImmutableSet<FlavorDomain<?>> FLAVOR_DOMAINS = ImmutableSet.of(
      JsFlavors.PLATFORM_DOMAIN,
      JsFlavors.OPTIMIZATION_DOMAIN,
      JsFlavors.RAM_BUNDLE_DOMAIN);

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return JsFlavors.validateFlavors(flavors, FLAVOR_DOMAINS);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(FLAVOR_DOMAINS);
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
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {

    final ImmutableSortedSet<Flavor> flavors = params.getBuildTarget().getFlavors();
    // For Android, we bundle JS output as assets, and images etc. as resources.
    // To facilitate this, we return a build rule that in turn depends on a `JsBundle` and
    // an `AndroidResource`. The `AndroidResource` rule also depends on the `JsBundle`
    // if the `FORCE_JS_BUNDLE` flavor is present, we create the `JsBundle` instance itself.
    if (flavors.contains(JsFlavors.ANDROID) && !flavors.contains(JsFlavors.FORCE_JS_BUNDLE)) {
      return createAndroidRule(params.copyInvalidatingDeps(), resolver, args.rDotJavaPackage);
    }

    // Flavors are propagated from js_bundle targets to their js_library dependencies
    // for that reason, dependencies of libraries are handled manually, and as a first step,
    // all dependencies to libraries are removed
    params = JsUtil.withWorkerDependencyOnly(params, resolver, args.worker);

    final Either<ImmutableSet<String>, String> entryPoint = args.entry;
    ImmutableSortedSet<BuildRule> libraryDeps =
        new TransitiveLibraryDependencies(params.getBuildTarget(), targetGraph, resolver)
            .collect(args.libs);

    return new JsBundle(
        params.copyAppendingExtraDeps(libraryDeps),
        libraryDeps.stream()
            .map(BuildRule::getSourcePathToOutput)
            .collect(MoreCollectors.toImmutableSortedSet()),
        entryPoint.isLeft() ? entryPoint.getLeft() : ImmutableSet.of(entryPoint.getRight()),
        args.bundleName.orElse(params.getBuildTarget().getShortName() + ".js"),
        resolver.getRuleWithType(args.worker, WorkerTool.class));
  }

  private static BuildRule createAndroidRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      Optional<String> rDotJavaPackage) throws NoSuchBuildTargetException {
    final BuildTarget bundleTarget = params.getBuildTarget()
        .withAppendedFlavors(JsFlavors.FORCE_JS_BUNDLE)
        .withoutFlavors(JsFlavors.ANDROID_RESOURCES);
    resolver.requireRule(bundleTarget);

    final JsBundle jsBundle = resolver.getRuleWithType(bundleTarget, JsBundle.class);
    if (params.getBuildTarget().getFlavors().contains(JsFlavors.ANDROID_RESOURCES)) {
      final String rDot = rDotJavaPackage.orElseThrow(() -> new HumanReadableException(
          "Specify `android_package` when building %s for Android.",
          params.getBuildTarget().getUnflavoredBuildTarget()));
      return createAndroidResources(params, resolver, jsBundle, rDot);
    } else {
      return createAndroidBundle(params, resolver, jsBundle);
    }
  }

  private static JsBundleAndroid createAndroidBundle(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      JsBundle jsBundle) throws NoSuchBuildTargetException {

    final BuildTarget resourceTarget = params.getBuildTarget()
        .withAppendedFlavors(JsFlavors.ANDROID_RESOURCES);
    final BuildRule resource = resolver.requireRule(resourceTarget);

    return new JsBundleAndroid(
        params.copyReplacingDeclaredAndExtraDeps(
            ImmutableSortedSet::of,
            () -> ImmutableSortedSet.of(jsBundle, resource)),
        jsBundle,
        resolver.getRuleWithType(resourceTarget, AndroidResource.class));
  }

  private static BuildRule createAndroidResources(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      JsBundle jsBundle,
      String rDotJavaPackage) throws NoSuchBuildTargetException {

    return new AndroidResource(
        params.copyReplacingDeclaredAndExtraDeps(
            ImmutableSortedSet::of,
            () -> ImmutableSortedSet.of(jsBundle)),
        new SourcePathRuleFinder(resolver),
        ImmutableSortedSet.of(), // deps
        jsBundle.getSourcePathToResources(),
        ImmutableSortedMap.of(), // resSrcs
        rDotJavaPackage,
        null,
        ImmutableSortedMap.of(),
        null,
        false);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<BuildTarget> libs;
    public Either<ImmutableSet<String>, String> entry;
    public Optional<String> bundleName;
    public BuildTarget worker;
    @Hint(name = "android_package")
    public Optional<String> rDotJavaPackage;
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
      final ImmutableSortedSet<Flavor> bundleFlavors = bundleTarget.getFlavors();
      extraFlavors = bundleFlavors.contains(JsFlavors.RELEASE)
          ? bundleFlavors.stream()
                .filter(flavor -> JsLibraryDescription.FLAVOR_DOMAINS.stream()
                    .anyMatch(domain -> domain.contains(flavor)))
                .collect(MoreCollectors.toImmutableSortedSet())
          : ImmutableSortedSet.of();
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

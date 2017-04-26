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

import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.apple.AppleBundleResources;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.HasAppleBundleResourcesDescription;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.ProjectFilesystem;
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
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Hint;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

public class JsBundleDescription implements
    Description<JsBundleDescription.Arg>,
    Flavored,
    HasAppleBundleResourcesDescription<JsBundleDescription.Arg> {

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
    ImmutableSortedSet<JsLibrary> libraryDeps = new TransitiveLibraryDependencies(
        params.getBuildTarget(),
        targetGraph,
        resolver,
        args.libs,
        args.deps).collect();

    return new JsBundle(
        params.copyAppendingExtraDeps(libraryDeps),
        libraryDeps.stream()
            .map(JsLibrary::getSourcePathToOutput)
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

  @Override
  public void addAppleBundleResources(
      AppleBundleResources.Builder builder,
      TargetNode<Arg, ?> targetNode,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver) {
    JsBundleOutputs bundle = resolver.getRuleWithType(
        targetNode.getBuildTarget(),
        JsBundleOutputs.class);
    builder.addDirsContainingResourceDirs(
        bundle.getSourcePathToOutput(),
        bundle.getSourcePathToResources());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableSortedSet<BuildTarget> libs = ImmutableSortedSet.of();
    public Either<ImmutableSet<String>, String> entry;
    public Optional<String> bundleName;
    public BuildTarget worker;
    @Hint(name = "android_package")
    public Optional<String> rDotJavaPackage;
  }

  private static class TransitiveLibraryDependencies
      extends AbstractBreadthFirstTraversal<TransitiveLibraryDependencies.Node> {
    private final ImmutableSortedSet<Flavor> extraFlavors;
    private final BuildRuleResolver resolver;
    private final SourcePathRuleFinder ruleFinder;
    private final TargetGraph targetGraph;
    private final ImmutableSortedSet.Builder<JsLibrary> jsLibraries =
        ImmutableSortedSet.naturalOrder();

    private TransitiveLibraryDependencies(
        BuildTarget bundleTarget,
        TargetGraph targetGraph,
        BuildRuleResolver resolver,
        Collection<BuildTarget> firstLevelLibraryDeps,
        Collection<BuildTarget> firstLevelDeps) {
      super(Stream.concat(firstLevelLibraryDeps.stream(), firstLevelDeps.stream())
          .map(t -> new Node(t, bundleTarget))
          .collect(MoreCollectors.toImmutableList()));

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

      // ensure that `libs` are actually `JsLibrary` rules. This will go away.
      firstLevelLibraryDeps.stream().forEach(t -> requireLibrary(new Node(t, bundleTarget)));
    }

    @Override
    public Iterable<Node> visit(Node node) throws RuntimeException {
      final TargetNode<?, ?> targetNode = targetGraph.get(node.target);
      final Description<?> description = targetNode.getDescription();

      Stream<BuildTarget> additionalTargets;
      if (description instanceof JsLibraryDescription) {
        final JsLibrary library = requireLibrary(node);
        jsLibraries.add(library);
        additionalTargets =  getLibraryDependencies(library);
      } else if (description instanceof AndroidLibraryDescription ||
          description instanceof AppleLibraryDescription) {
        additionalTargets = targetNode.getDeclaredDeps().stream();
      } else {
        additionalTargets = Stream.empty();
      }

      return additionalTargets
          .map(t -> new Node(t, node.target))
          .collect(MoreCollectors.toImmutableList());
    }

    ImmutableSortedSet<JsLibrary> collect() {
      start();
      return jsLibraries.build();
    }

    private JsLibrary requireLibrary(Node node) {
      try {
        return JsUtil.verifyIsJsLibrary(
            resolver.requireRule(node.target.withAppendedFlavors(extraFlavors)),
            node.parent,
            targetGraph);
      } catch (NoSuchBuildTargetException e) {
        throw new HumanReadableException(e);
      }
    }

    private Stream<BuildTarget> getLibraryDependencies(JsLibrary library) {
      return library.getLibraryDependencies()
          .stream()
          .map(sourcePath ->
              ruleFinder.getRule(sourcePath).orElseThrow(() -> new HumanReadableException(
                  "js_library %s has '%s' as a lib, but js_library can only have other " +
                      "js_library targets as lib",
                  library.getBuildTarget(),
                  sourcePath)
              ).getBuildTarget());
    }

    public static class Node {
      final BuildTarget target;
      final BuildTarget parent;

      public Node(BuildTarget target, BuildTarget parent) {
        this.target = target;
        this.parent = parent;
      }
    }
  }
}

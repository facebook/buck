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

import com.facebook.buck.android.Aapt2Compile;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.apple.AppleBundleResources;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.HasAppleBundleResourcesDescription;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Collection;
import java.util.Optional;
import org.immutables.value.Value;

public class JsBundleDescription
    implements Description<JsBundleDescriptionArg>,
        Flavored,
        HasAppleBundleResourcesDescription<JsBundleDescriptionArg>,
        JsBundleOutputsDescription<JsBundleDescriptionArg> {

  static final ImmutableSet<FlavorDomain<?>> FLAVOR_DOMAINS =
      ImmutableSet.of(
          JsFlavors.PLATFORM_DOMAIN,
          JsFlavors.OPTIMIZATION_DOMAIN,
          JsFlavors.RAM_BUNDLE_DOMAIN,
          JsFlavors.OUTPUT_OPTIONS_DOMAIN);

  private final ToolchainProvider toolchainProvider;

  public JsBundleDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return supportsFlavors(flavors);
  }

  static boolean supportsFlavors(ImmutableSet<Flavor> flavors) {
    return JsFlavors.validateFlavors(flavors, FLAVOR_DOMAINS);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(FLAVOR_DOMAINS);
  }

  @Override
  public Class<JsBundleDescriptionArg> getConstructorArgType() {
    return JsBundleDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      JsBundleDescriptionArg args) {

    final ImmutableSortedSet<Flavor> flavors = buildTarget.getFlavors();

    // Source maps are exposed individually using a special flavor
    if (flavors.contains(JsFlavors.SOURCE_MAP)) {
      BuildTarget bundleTarget = buildTarget.withoutFlavors(JsFlavors.SOURCE_MAP);
      resolver.requireRule(bundleTarget);
      JsBundleOutputs bundleOutputs = resolver.getRuleWithType(bundleTarget, JsBundleOutputs.class);

      return new ExportFile(
          buildTarget,
          projectFilesystem,
          new SourcePathRuleFinder(resolver),
          bundleOutputs.getBundleName() + ".map",
          ExportFileDescription.Mode.REFERENCE,
          bundleOutputs.getSourcePathToSourceMap());
    }

    if (flavors.contains(JsFlavors.MISC)) {
      BuildTarget bundleTarget = buildTarget.withoutFlavors(JsFlavors.MISC);
      resolver.requireRule(bundleTarget);
      JsBundleOutputs bundleOutputs = resolver.getRuleWithType(bundleTarget, JsBundleOutputs.class);

      return new ExportFile(
          buildTarget,
          projectFilesystem,
          new SourcePathRuleFinder(resolver),
          bundleOutputs.getBundleName() + "-misc",
          ExportFileDescription.Mode.REFERENCE,
          bundleOutputs.getSourcePathToMisc());
    }

    // For Android, we bundle JS output as assets, and images etc. as resources.
    // To facilitate this, we return a build rule that in turn depends on a `JsBundle` and
    // an `AndroidResource`. The `AndroidResource` rule also depends on the `JsBundle`
    // if the `FORCE_JS_BUNDLE` flavor is present, we create the `JsBundle` instance itself.
    if (flavors.contains(JsFlavors.ANDROID)
        && !flavors.contains(JsFlavors.FORCE_JS_BUNDLE)
        && !flavors.contains(JsFlavors.DEPENDENCY_FILE)) {
      return createAndroidRule(
          toolchainProvider, buildTarget, projectFilesystem, resolver, args.getAndroidPackage());
    }

    // Flavors are propagated from js_bundle targets to their js_library dependencies
    // for that reason, dependencies of libraries are handled manually, and as a first step,
    // all dependencies to libraries are removed
    params = JsUtil.withWorkerDependencyOnly(params, resolver, args.getWorker());

    final Either<ImmutableSet<String>, String> entryPoint = args.getEntry();
    TransitiveLibraryDependencies libsResolver =
        new TransitiveLibraryDependencies(buildTarget, targetGraph, resolver);
    ImmutableSortedSet<JsLibrary> libraryDeps = libsResolver.collect(args.getDeps());

    BuildRuleParams paramsWithLibraries = params.copyAppendingExtraDeps(libraryDeps);
    ImmutableSortedSet<SourcePath> libraries =
        libraryDeps
            .stream()
            .map(JsLibrary::getSourcePathToOutput)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    ImmutableSet<String> entryPoints =
        entryPoint.isLeft() ? entryPoint.getLeft() : ImmutableSet.of(entryPoint.getRight());

    // If {@link JsFlavors.DEPENDENCY_FILE} is specified, the worker will output a file containing
    // all dependencies between files that go into the final bundle
    if (flavors.contains(JsFlavors.DEPENDENCY_FILE)) {
      return new JsDependenciesFile(
          buildTarget,
          projectFilesystem,
          paramsWithLibraries,
          libraries,
          entryPoints,
          resolver.getRuleWithType(args.getWorker(), WorkerTool.class));
    }

    ImmutableList<ImmutableSet<SourcePath>> libraryPathGroups =
        args.getLibraryGroups()
            .stream()
            .map(
                group ->
                    group
                        .stream()
                        .map(
                            lib ->
                                (SourcePath)
                                    libsResolver.requireLibrary(lib).getSourcePathToOutput())
                        .collect(ImmutableSet.toImmutableSet()))
            .collect(ImmutableList.toImmutableList());

    String bundleName = getBundleName(args, buildTarget.getFlavors());

    return new JsBundle(
        buildTarget,
        projectFilesystem,
        paramsWithLibraries,
        libraries,
        entryPoints,
        JsUtil.getExtraJson(args, buildTarget, resolver, cellRoots),
        libraryPathGroups,
        bundleName,
        resolver.getRuleWithType(args.getWorker(), WorkerTool.class));
  }

  private static BuildRule createAndroidRule(
      ToolchainProvider toolchainProvider,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      Optional<String> rDotJavaPackage) {
    final BuildTarget bundleTarget =
        buildTarget
            .withAppendedFlavors(JsFlavors.FORCE_JS_BUNDLE)
            .withoutFlavors(JsFlavors.ANDROID_RESOURCES)
            .withoutFlavors(AndroidResourceDescription.AAPT2_COMPILE_FLAVOR);
    resolver.requireRule(bundleTarget);

    final JsBundle jsBundle = resolver.getRuleWithType(bundleTarget, JsBundle.class);
    if (buildTarget.getFlavors().contains(JsFlavors.ANDROID_RESOURCES)) {
      final String rDot =
          rDotJavaPackage.orElseThrow(
              () ->
                  new HumanReadableException(
                      "Specify `android_package` when building %s for Android.",
                      buildTarget.getUnflavoredBuildTarget()));
      return createAndroidResources(
          toolchainProvider, buildTarget, projectFilesystem, resolver, jsBundle, rDot);
    } else {
      return createAndroidBundle(buildTarget, projectFilesystem, resolver, jsBundle);
    }
  }

  private static JsBundleAndroid createAndroidBundle(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      JsBundle jsBundle) {

    final BuildTarget resourceTarget = buildTarget.withAppendedFlavors(JsFlavors.ANDROID_RESOURCES);
    final BuildRule resource = resolver.requireRule(resourceTarget);

    return new JsBundleAndroid(
        buildTarget,
        projectFilesystem,
        new BuildRuleParams(
            () -> ImmutableSortedSet.of(),
            () -> ImmutableSortedSet.of(jsBundle, resource),
            ImmutableSortedSet.of()),
        jsBundle,
        resolver.getRuleWithType(resourceTarget, AndroidResource.class));
  }

  private static BuildRule createAndroidResources(
      ToolchainProvider toolchainProvider,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      JsBundle jsBundle,
      String rDotJavaPackage) {
    if (buildTarget.getFlavors().contains(AndroidResourceDescription.AAPT2_COMPILE_FLAVOR)) {
      AndroidPlatformTarget androidPlatformTarget =
          toolchainProvider.getByName(
              AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);
      return new Aapt2Compile(
          buildTarget,
          projectFilesystem,
          androidPlatformTarget,
          ImmutableSortedSet.of(jsBundle),
          jsBundle.getSourcePathToResources());
    }

    BuildRuleParams params =
        new BuildRuleParams(
            () -> ImmutableSortedSet.of(),
            () -> ImmutableSortedSet.of(jsBundle),
            ImmutableSortedSet.of());

    return new AndroidResource(
        buildTarget,
        projectFilesystem,
        params,
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
      TargetNode<JsBundleDescriptionArg, ?> targetNode,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver) {
    JsBundleOutputs bundle =
        resolver.getRuleWithType(targetNode.getBuildTarget(), JsBundleOutputs.class);
    addAppleBundleResources(builder, bundle);
  }

  static void addAppleBundleResources(
      AppleBundleResources.Builder builder, JsBundleOutputs bundle) {
    builder.addDirsContainingResourceDirs(
        bundle.getSourcePathToOutput(), bundle.getSourcePathToResources());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJsBundleDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps, HasExtraJson {

    Either<ImmutableSet<String>, String> getEntry();

    @Value.Default
    default String getBundleName() {
      return getName() + ".js";
    }

    ImmutableList<Pair<Flavor, String>> getBundleNameForFlavor();

    BuildTarget getWorker();

    /** For R.java */
    Optional<String> getAndroidPackage();

    /**
     * Get the ordered list of library groups that should be bundled together, in the case of
     * "bundle splitting".
     */
    ImmutableList<ImmutableSet<BuildTarget>> getLibraryGroups();
  }

  private static String getBundleName(
      JsBundleDescriptionArg args, ImmutableSortedSet<Flavor> flavors) {
    for (Pair<Flavor, String> nameForFlavor : args.getBundleNameForFlavor()) {
      if (flavors.contains(nameForFlavor.getFirst())) {
        return nameForFlavor.getSecond();
      }
    }
    return args.getBundleName();
  }

  private static class TransitiveLibraryDependencies {
    private final ImmutableSortedSet<Flavor> extraFlavors;
    private final BuildRuleResolver resolver;
    private final SourcePathRuleFinder ruleFinder;
    private final TargetGraph targetGraph;

    private TransitiveLibraryDependencies(
        BuildTarget bundleTarget, TargetGraph targetGraph, BuildRuleResolver resolver) {
      this.targetGraph = targetGraph;
      this.resolver = resolver;

      final ImmutableSortedSet<Flavor> bundleFlavors = bundleTarget.getFlavors();
      extraFlavors =
          bundleFlavors
              .stream()
              .filter(
                  flavor ->
                      JsLibraryDescription.FLAVOR_DOMAINS
                          .stream()
                          .anyMatch(domain -> domain.contains(flavor)))
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
      ruleFinder = new SourcePathRuleFinder(resolver);
    }

    ImmutableSortedSet<JsLibrary> collect(Collection<BuildTarget> deps) {
      ImmutableSortedSet.Builder<JsLibrary> jsLibraries = ImmutableSortedSet.naturalOrder();

      new AbstractBreadthFirstTraversal<BuildTarget>(deps) {
        @Override
        public Iterable<BuildTarget> visit(BuildTarget target) throws RuntimeException {
          final TargetNode<?, ?> targetNode = targetGraph.get(target);
          final Description<?> description = targetNode.getDescription();

          if (description instanceof JsLibraryDescription) {
            final JsLibrary library = requireLibrary(target);
            jsLibraries.add(library);
            return getLibraryDependencies(library);
          } else if (description instanceof AndroidLibraryDescription
              || description instanceof AppleLibraryDescription) {
            return targetNode.getDeclaredDeps();
          }

          return ImmutableList.of();
        }
      }.start();

      return jsLibraries.build();
    }

    private JsLibrary requireLibrary(BuildTarget target) {
      BuildRule rule = resolver.requireRule(target.withAppendedFlavors(extraFlavors));
      Preconditions.checkState(rule instanceof JsLibrary);
      return (JsLibrary) rule;
    }

    private Iterable<BuildTarget> getLibraryDependencies(JsLibrary library) {
      return library
          .getLibraryDependencies()
          .stream()
          .map(
              sourcePath ->
                  ruleFinder
                      .getRule(sourcePath)
                      .<HumanReadableException>orElseThrow(
                          () ->
                              new HumanReadableException(
                                  "js_library %s has '%s' as a lib, but js_library can only have other "
                                      + "js_library targets as lib",
                                  library.getBuildTarget(), sourcePath)))
          .map(BuildRule::getBuildTarget)
          .collect(ImmutableList.toImmutableList());
    }
  }
}

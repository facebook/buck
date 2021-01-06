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

package com.facebook.buck.features.js;

import com.facebook.buck.android.Aapt2Compile;
import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.apple.AppleBundleResources;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.HasAppleBundleResourcesDescription;
import com.facebook.buck.apple.SourcePathWithAppleBundleDestination;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.ExportFileDirectoryAction;
import com.facebook.buck.shell.ProvidesWorkerTool;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class JsBundleDescription
    implements DescriptionWithTargetGraph<JsBundleDescriptionArg>,
        Flavored,
        HasAppleBundleResourcesDescription<JsBundleDescriptionArg>,
        JsBundleOutputsDescription<JsBundleDescriptionArg>,
        ImplicitDepsInferringDescription<JsBundleDescriptionArg> {

  static final ImmutableSet<FlavorDomain<?>> FLAVOR_DOMAINS =
      ImmutableSet.of(
          JsFlavors.PLATFORM_DOMAIN,
          JsFlavors.OPTIMIZATION_DOMAIN,
          JsFlavors.RAM_BUNDLE_DOMAIN,
          JsFlavors.OUTPUT_OPTIONS_DOMAIN);

  private final ToolchainProvider toolchainProvider;
  private final AndroidBuckConfig androidBuckConfig;

  public JsBundleDescription(
      ToolchainProvider toolchainProvider, AndroidBuckConfig androidBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.androidBuckConfig = androidBuckConfig;
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return supportsFlavors(flavors);
  }

  static boolean supportsFlavors(ImmutableSet<Flavor> flavors) {
    return JsFlavors.validateFlavors(flavors, FLAVOR_DOMAINS);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(FLAVOR_DOMAINS);
  }

  @Override
  public Class<JsBundleDescriptionArg> getConstructorArgType() {
    return JsBundleDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JsBundleDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    FlavorSet flavors = buildTarget.getFlavors();

    // Source maps are exposed individually using a special flavor
    if (flavors.contains(JsFlavors.SOURCE_MAP)) {
      BuildTarget bundleTarget = buildTarget.withoutFlavors(JsFlavors.SOURCE_MAP);
      graphBuilder.requireRule(bundleTarget);
      JsBundleOutputs bundleOutputs =
          graphBuilder.getRuleWithType(bundleTarget, JsBundleOutputs.class);

      return new ExportFile(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          bundleOutputs.getBundleName() + ".map",
          ExportFileDescription.Mode.REFERENCE,
          bundleOutputs.getSourcePathToSourceMap(),
          ExportFileDirectoryAction.FAIL);
    }

    if (flavors.contains(JsFlavors.MISC)) {
      BuildTarget bundleTarget = buildTarget.withoutFlavors(JsFlavors.MISC);
      graphBuilder.requireRule(bundleTarget);
      JsBundleOutputs bundleOutputs =
          graphBuilder.getRuleWithType(bundleTarget, JsBundleOutputs.class);

      return new ExportFile(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          bundleOutputs.getBundleName() + "-misc",
          ExportFileDescription.Mode.REFERENCE,
          bundleOutputs.getSourcePathToMisc(),
          // TODO(27131551): temporary allow directory export until a proper fix is implemented
          ExportFileDirectoryAction.ALLOW);
    }

    // For Android, we bundle JS output as assets, and images etc. as resources.
    // To facilitate this, we return a build rule that in turn depends on a `JsBundle` and
    // an `AndroidResource`. The `AndroidResource` rule also depends on the `JsBundle`
    // if the `FORCE_JS_BUNDLE` flavor is present, we create the `JsBundle` instance itself.
    if (flavors.contains(JsFlavors.ANDROID)
        && !flavors.contains(JsFlavors.FORCE_JS_BUNDLE)
        && !flavors.contains(JsFlavors.DEPENDENCY_FILE)) {
      return createAndroidRule(
          toolchainProvider,
          buildTarget,
          projectFilesystem,
          graphBuilder,
          args.getAndroidPackage());
    }

    Either<ImmutableSet<String>, String> entryPoint = args.getEntry();
    TransitiveLibraryDependencies libsResolver =
        new TransitiveLibraryDependencies(buildTarget, context.getTargetGraph(), graphBuilder);
    ImmutableSet<JsLibrary> flavoredLibraryDeps = libsResolver.collect(args.getDeps());
    Stream<BuildRule> generatedDeps =
        findGeneratedSources(graphBuilder, flavoredLibraryDeps.stream())
            .map(graphBuilder::requireRule);

    // Flavors are propagated from js_bundle targets to their js_library dependencies
    // for that reason, dependencies of libraries are handled manually, and as a first step,
    // all dependencies to libraries are replaced with dependencies to flavored library targets.
    BuildRuleParams paramsWithFlavoredLibraries =
        params
            .withoutDeclaredDeps()
            .copyAppendingExtraDeps(
                Stream.concat(flavoredLibraryDeps.stream(), generatedDeps)::iterator);
    ImmutableSortedSet<SourcePath> libraries =
        flavoredLibraryDeps.stream()
            .map(JsLibrary::getSourcePathToOutput)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    ImmutableSet<String> entryPoints =
        entryPoint.isLeft() ? entryPoint.getLeft() : ImmutableSet.of(entryPoint.getRight());

    Optional<Arg> extraJson =
        JsUtil.getExtraJson(args, buildTarget, graphBuilder, context.getCellPathResolver());

    // If {@link JsFlavors.DEPENDENCY_FILE} is specified, the worker will output a file containing
    // all dependencies between files that go into the final bundle
    if (flavors.contains(JsFlavors.DEPENDENCY_FILE)) {
      return new JsDependenciesFile(
          buildTarget,
          projectFilesystem,
          paramsWithFlavoredLibraries,
          libraries,
          entryPoints,
          extraJson,
          graphBuilder.getRuleWithType(args.getWorker(), ProvidesWorkerTool.class).getWorkerTool());
    }

    String bundleName =
        args.computeBundleName(buildTarget.getFlavors().getSet(), () -> args.getName() + ".js");

    return new JsBundle(
        buildTarget,
        projectFilesystem,
        paramsWithFlavoredLibraries,
        libraries,
        entryPoints,
        extraJson,
        bundleName,
        graphBuilder.getRuleWithType(args.getWorker(), ProvidesWorkerTool.class).getWorkerTool());
  }

  private BuildRule createAndroidRule(
      ToolchainProvider toolchainProvider,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      Optional<String> rDotJavaPackage) {
    BuildTarget bundleTarget =
        buildTarget
            .withAppendedFlavors(JsFlavors.FORCE_JS_BUNDLE)
            .withoutFlavors(JsFlavors.ANDROID_RESOURCES)
            .withoutFlavors(AndroidResourceDescription.AAPT2_COMPILE_FLAVOR);
    graphBuilder.requireRule(bundleTarget);

    JsBundle jsBundle = graphBuilder.getRuleWithType(bundleTarget, JsBundle.class);
    if (buildTarget.getFlavors().contains(JsFlavors.ANDROID_RESOURCES)) {
      String rDot =
          rDotJavaPackage.orElseThrow(
              () ->
                  new HumanReadableException(
                      "Specify `android_package` when building %s for Android.",
                      buildTarget.getUnflavoredBuildTarget()));
      return createAndroidResources(
          toolchainProvider, buildTarget, projectFilesystem, graphBuilder, jsBundle, rDot);
    } else {
      return createAndroidBundle(buildTarget, projectFilesystem, graphBuilder, jsBundle);
    }
  }

  private static JsBundleAndroid createAndroidBundle(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      JsBundle jsBundle) {

    BuildTarget resourceTarget = buildTarget.withAppendedFlavors(JsFlavors.ANDROID_RESOURCES);
    BuildRule resource = graphBuilder.requireRule(resourceTarget);

    return new JsBundleAndroid(
        buildTarget,
        projectFilesystem,
        new BuildRuleParams(
            ImmutableSortedSet::of,
            () -> ImmutableSortedSet.of(jsBundle, resource),
            ImmutableSortedSet.of()),
        jsBundle,
        graphBuilder.getRuleWithType(resourceTarget, AndroidResource.class));
  }

  private BuildRule createAndroidResources(
      ToolchainProvider toolchainProvider,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      JsBundle jsBundle,
      String rDotJavaPackage) {
    if (buildTarget.getFlavors().contains(AndroidResourceDescription.AAPT2_COMPILE_FLAVOR)) {
      AndroidPlatformTarget androidPlatformTarget =
          toolchainProvider.getByName(
              AndroidPlatformTarget.DEFAULT_NAME,
              buildTarget.getTargetConfiguration(),
              AndroidPlatformTarget.class);
      ToolProvider aapt2ToolProvider = androidPlatformTarget.getAapt2ToolProvider();
      return new Aapt2Compile(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          aapt2ToolProvider.resolve(graphBuilder, buildTarget.getTargetConfiguration()),
          jsBundle.getSourcePathToResources(),
          /* skipCrunchPngs */ false,
          androidBuckConfig.getFailOnLegacyAaptErrors());
    }

    BuildRuleParams params =
        new BuildRuleParams(
            ImmutableSortedSet::of, () -> ImmutableSortedSet.of(jsBundle), ImmutableSortedSet.of());

    return new AndroidResource(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        ImmutableSortedSet.of(), // deps
        jsBundle.getSourcePathToResources(),
        ImmutableSortedMap.of(), // resSrcs
        rDotJavaPackage,
        null,
        ImmutableSortedMap.of(),
        null,
        false);
  }

  /**
   * Finds all build targets that are inputs to any transitive JsFile dependency of any of the
   * passed in JsLibrary instances.
   */
  private static Stream<BuildTarget> findGeneratedSources(
      SourcePathRuleFinder ruleFinder, Stream<JsLibrary> libraries) {
    return libraries
        .flatMap(lib -> lib.getJsFiles(ruleFinder))
        .map(jsFile -> jsFile.getSourceBuildTarget(ruleFinder))
        .filter(Objects::nonNull);
  }

  @Override
  public void addAppleBundleResources(
      AppleBundleResources.Builder builder,
      TargetNode<JsBundleDescriptionArg> targetNode,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver) {
    JsBundleOutputs bundle =
        resolver.getRuleWithType(targetNode.getBuildTarget(), JsBundleOutputs.class);
    addAppleBundleResources(builder, bundle);
  }

  static void addAppleBundleResourcesJSOutputOnly(
      AppleBundleResources.Builder builder, JsBundleOutputs bundle) {
    builder.addDirsContainingResourceDirs(
        SourcePathWithAppleBundleDestination.of(bundle.getSourcePathToOutput()));
  }

  static void addAppleBundleResources(
      AppleBundleResources.Builder builder, JsBundleOutputs bundle) {
    addAppleBundleResourcesJSOutputOnly(builder, bundle);
    builder.addDirsContainingResourceDirs(
        SourcePathWithAppleBundleDestination.of(bundle.getSourcePathToResources()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      JsBundleDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    AndroidTools.addParseTimeDepsToAndroidTools(
        toolchainProvider, buildTarget, targetGraphOnlyDepsBuilder);
  }

  @RuleArg
  interface AbstractJsBundleDescriptionArg
      extends BuildRuleArg, HasDeclaredDeps, HasExtraJson, HasBundleName {

    Either<ImmutableSet<String>, String> getEntry();

    BuildTarget getWorker();

    /** For R.java */
    Optional<String> getAndroidPackage();
  }

  private static class TransitiveLibraryDependencies {
    private final ImmutableSortedSet<Flavor> extraFlavors;
    private final ActionGraphBuilder graphBuilder;
    private final TargetGraph targetGraph;

    private TransitiveLibraryDependencies(
        BuildTarget bundleTarget, TargetGraph targetGraph, ActionGraphBuilder graphBuilder) {
      this.targetGraph = targetGraph;
      this.graphBuilder = graphBuilder;

      FlavorSet bundleFlavors = bundleTarget.getFlavors();
      extraFlavors =
          bundleFlavors.getSet().stream()
              .filter(
                  flavor ->
                      JsLibraryDescription.FLAVOR_DOMAINS.stream()
                          .anyMatch(domain -> domain.contains(flavor)))
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    }

    ImmutableSet<JsLibrary> collect(Collection<BuildTarget> deps) {
      ImmutableSet.Builder<JsLibrary> jsLibraries = ImmutableSet.builder();

      new AbstractBreadthFirstTraversal<BuildTarget>(deps) {
        @Override
        public Iterable<BuildTarget> visit(BuildTarget target) throws RuntimeException {
          TargetNode<?> targetNode = targetGraph.get(target);
          BaseDescription<?> description = targetNode.getDescription();

          if (description instanceof JsLibraryDescription) {
            JsLibrary library = requireLibrary(target);
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
      BuildRule rule = graphBuilder.requireRule(target.withAppendedFlavors(extraFlavors));
      Preconditions.checkState(rule instanceof JsLibrary);
      return (JsLibrary) rule;
    }

    private Iterable<BuildTarget> getLibraryDependencies(JsLibrary library) {
      ImmutableSortedSet<SourcePath> libraryDependencies = library.getLibraryDependencies();
      ImmutableList.Builder<BuildTarget> libraryTargets =
          ImmutableList.builderWithExpectedSize(libraryDependencies.size());
      for (SourcePath sourcePath : libraryDependencies) {
        Optional<BuildRule> rule = graphBuilder.getRule(sourcePath);
        if (rule.isPresent()) {
          libraryTargets.add(rule.get().getBuildTarget());
        } else {
          throw new HumanReadableException(
              "js_library %s has '%s' as a lib, but js_library can only have other "
                  + "js_library targets as lib",
              library.getBuildTarget(), sourcePath);
        }
      }
      return libraryTargets.build();
    }
  }
}

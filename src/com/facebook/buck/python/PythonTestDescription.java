/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python;

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class PythonTestDescription implements
    Description<PythonTestDescription.Arg>,
    ImplicitDepsInferringDescription<PythonTestDescription.Arg>,
    VersionRoot<PythonTestDescription.Arg> {

  private static final Flavor BINARY_FLAVOR = ImmutableFlavor.of("binary");

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.of(
              "location", new LocationMacroExpander()));

  private final PythonBinaryDescription binaryDescription;
  private final PythonBuckConfig pythonBuckConfig;
  private final FlavorDomain<PythonPlatform> pythonPlatforms;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PythonTestDescription(
      PythonBinaryDescription binaryDescription,
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      Optional<Long> defaultTestRuleTimeoutMs,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.binaryDescription = binaryDescription;
    this.pythonBuckConfig = pythonBuckConfig;
    this.pythonPlatforms = pythonPlatforms;
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  protected static Path getTestMainName() {
    return Paths.get("__test_main__.py");
  }

  @VisibleForTesting
  protected static Path getTestModulesListName() {
    return Paths.get("__test_modules__.py");
  }

  @VisibleForTesting
  protected static Path getTestModulesListPath(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "%s").resolve(getTestModulesListName());
  }

  @VisibleForTesting
  protected static BuildTarget getBinaryBuildTarget(BuildTarget target) {
    return BuildTargets.createFlavoredBuildTarget(target.checkUnflavored(), BINARY_FLAVOR);
  }

  /**
   * Create the contents of a python source file that just contains a list of
   * the given test modules.
   */
  private static String getTestModulesListContents(ImmutableSet<String> modules) {
    String contents = "TEST_MODULES = [\n";
    for (String module : modules) {
      contents += String.format("    \"%s\",\n", module);
    }
    contents += "]";
    return contents;
  }

  /**
   * Return a {@link BuildRule} that constructs the source file which contains the list
   * of test modules this python test rule will run.  Setting up a separate build rule
   * for this allows us to use the existing python binary rule without changes to account
   * for the build-time creation of this file.
   */
  private static BuildRule createTestModulesSourceBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      Path outputPath,
      ImmutableSet<String> testModules) {

    // Modify the build rule params to change the target, type, and remove all deps.
    BuildRuleParams newParams = params.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(
            params.getBuildTarget().checkUnflavored(),
            ImmutableFlavor.of("test_module")),
        Suppliers.ofInstance(ImmutableSortedSet.of()),
        Suppliers.ofInstance(ImmutableSortedSet.of()));

    String contents = getTestModulesListContents(testModules);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    return new WriteFile(
        newParams,
        pathResolver,
        contents,
        outputPath,
        /* executable */ false);
  }

  @Override
  public <A extends Arg> PythonTest createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) throws HumanReadableException, NoSuchBuildTargetException {

    PythonPlatform pythonPlatform =
        pythonPlatforms.getValue(params.getBuildTarget()).orElse(
            pythonPlatforms.getValue(
                args.platform.<Flavor>map(ImmutableFlavor::of).orElse(
                    pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(params.getBuildTarget()).orElse(
        defaultCxxPlatform);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    Optional<ImmutableMap<BuildTarget, Version>> selectedVersions =
        targetGraph.get(params.getBuildTarget()).getSelectedVersions();

    ImmutableMap<Path, SourcePath> srcs =
        PythonUtil.getModules(
            params.getBuildTarget(),
            pathResolver,
            "srcs",
            baseModule,
            args.srcs,
            args.platformSrcs,
            pythonPlatform,
            args.versionedSrcs,
            selectedVersions);

    ImmutableMap<Path, SourcePath> resources =
        PythonUtil.getModules(
            params.getBuildTarget(),
            pathResolver,
            "resources",
            baseModule,
            args.resources,
            args.platformResources,
            pythonPlatform,
            args.versionedResources,
            selectedVersions);

    // Convert the passed in module paths into test module names.
    ImmutableSet.Builder<String> testModulesBuilder = ImmutableSet.builder();
    for (Path name : srcs.keySet()) {
      testModulesBuilder.add(
          PythonUtil.toModuleName(params.getBuildTarget(), name.toString()));
    }
    ImmutableSet<String> testModules = testModulesBuilder.build();

    // Construct a build rule to generate the test modules list source file and
    // add it to the build.
    BuildRule testModulesBuildRule = createTestModulesSourceBuildRule(
        params,
        resolver,
        getTestModulesListPath(params.getBuildTarget(), params.getProjectFilesystem()),
        testModules);
    resolver.addToIndex(testModulesBuildRule);

    String mainModule;
    if (args.mainModule.isPresent()) {
      mainModule = args.mainModule.get();
    } else {
      mainModule = PythonUtil.toModuleName(params.getBuildTarget(), getTestMainName().toString());
    }

    // Build up the list of everything going into the python test.
    PythonPackageComponents testComponents = PythonPackageComponents.of(
        ImmutableMap
            .<Path, SourcePath>builder()
            .put(
                getTestModulesListName(),
                new BuildTargetSourcePath(testModulesBuildRule.getBuildTarget()))
            .put(
                getTestMainName(),
                pythonBuckConfig.getPathToTestMain(params.getProjectFilesystem()))
            .putAll(srcs)
            .build(),
        resources,
        ImmutableMap.of(),
        ImmutableSet.of(),
        args.zipSafe);
    PythonPackageComponents allComponents =
        PythonUtil.getAllComponents(
            params,
            resolver,
            pathResolver,
            ruleFinder,
            testComponents,
            pythonPlatform,
            cxxBuckConfig,
            cxxPlatform,
            args.linkerFlags.stream()
                .map(MacroArg.toMacroArgFunction(
                    PythonUtil.MACRO_HANDLER,
                    params.getBuildTarget(),
                    params.getCellRoots(),
                    resolver)::apply)
                .collect(MoreCollectors.toImmutableList()),
            pythonBuckConfig.getNativeLinkStrategy(),
            args.preloadDeps);

    // Build the PEX using a python binary rule with the minimum dependencies.
    BuildRuleParams binaryParams = params.copyWithChanges(
        getBinaryBuildTarget(params.getBuildTarget()),
        Suppliers.ofInstance(PythonUtil.getDepsFromComponents(ruleFinder, allComponents)),
        Suppliers.ofInstance(ImmutableSortedSet.of()));
    PythonBinary binary =
        binaryDescription.createPackageRule(
            binaryParams,
            resolver,
            pathResolver,
            ruleFinder,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            args.extension,
            allComponents,
            args.buildArgs,
            args.packageStyle.orElse(pythonBuckConfig.getPackageStyle()),
            PythonUtil.getPreloadNames(
                resolver,
                cxxPlatform,
                args.preloadDeps));
    resolver.addToIndex(binary);

    ImmutableList.Builder<Pair<Float, ImmutableSet<Path>>> neededCoverageBuilder =
        ImmutableList.builder();
    for (NeededCoverageSpec coverageSpec : args.neededCoverage) {
        BuildRule buildRule = resolver.getRule(coverageSpec.getBuildTarget());
        if (params.getDeps().contains(buildRule) &&
            buildRule instanceof PythonLibrary) {
          PythonLibrary pythonLibrary = (PythonLibrary) buildRule;
          ImmutableSortedSet<Path> paths;
          if (coverageSpec.getPathName().isPresent()) {
            Path path = coverageSpec.getBuildTarget().getBasePath().resolve(
                coverageSpec.getPathName().get());
            if (!pythonLibrary.getSrcs(pythonPlatform).keySet().contains(path)) {
              throw new HumanReadableException(
                  "%s: path %s specified in needed_coverage not found in target %s",
                  params.getBuildTarget(),
                  path,
                  buildRule.getBuildTarget());
            }
            paths = ImmutableSortedSet.of(path);
          } else {
            paths = ImmutableSortedSet.copyOf(pythonLibrary.getSrcs(pythonPlatform).keySet());
          }
          neededCoverageBuilder.add(
              new Pair<Float, ImmutableSet<Path>>(
                  coverageSpec.getNeededCoverageRatio(),
                  paths));
        } else {
            throw new HumanReadableException(
                    "%s: needed_coverage requires a python library dependency. Found %s instead",
                    params.getBuildTarget(), buildRule);
        }
    }

    // Supplier which expands macros in the passed in test environment.
    Supplier<ImmutableMap<String, String>> testEnv =
        () -> ImmutableMap.copyOf(
            Maps.transformValues(
                args.env,
                MACRO_HANDLER.getExpander(
                    params.getBuildTarget(),
                    params.getCellRoots(),
                    resolver)));

    // Generate and return the python test rule, which depends on the python binary rule above.
    return new PythonTest(
        params.copyWithDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(params.getDeclaredDeps().get())
                    .add(binary)
                    .build()),
            params.getExtraDeps()),
        pathResolver,
        ruleFinder,
        testEnv,
        binary,
        args.labels,
        neededCoverageBuilder.build(),
        args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.contacts);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    ImmutableList.Builder<BuildTarget> targets = ImmutableList.builder();

    // We need to use the C/C++ linker for native libs handling, so add in the C/C++ linker to
    // parse time deps.
    targets.addAll(
        cxxPlatforms.getValue(buildTarget).orElse(defaultCxxPlatform).getLd().getParseTimeDeps());

    if (constructorArg.packageStyle.orElse(pythonBuckConfig.getPackageStyle()) ==
        PythonBuckConfig.PackageStyle.STANDALONE) {
      targets.addAll(OptionalCompat.asSet(pythonBuckConfig.getPexTarget()));
      targets.addAll(OptionalCompat.asSet(pythonBuckConfig.getPexExecutorTarget()));
    }

    return targets.build();
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends PythonLibraryDescription.Arg {
    public Optional<String> mainModule;
    public ImmutableSet<String> contacts = ImmutableSet.of();
    public ImmutableSet<Label> labels = ImmutableSet.of();
    public Optional<String> platform;
    public Optional<String> extension;
    public Optional<PythonBuckConfig.PackageStyle> packageStyle;
    public ImmutableSet<BuildTarget> preloadDeps = ImmutableSet.of();
    public ImmutableList<String> linkerFlags = ImmutableList.of();
    public ImmutableList<NeededCoverageSpec> neededCoverage = ImmutableList.of();

    public ImmutableList<String> buildArgs = ImmutableList.of();

    public ImmutableMap<String, String> env = ImmutableMap.of();
    public Optional<Long> testRuleTimeoutMs;
    public Optional<String> versionUniverse;
  }

}

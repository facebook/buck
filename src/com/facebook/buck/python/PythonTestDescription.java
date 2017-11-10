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

import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

public class PythonTestDescription
    implements Description<PythonTestDescriptionArg>,
        ImplicitDepsInferringDescription<PythonTestDescription.AbstractPythonTestDescriptionArg>,
        VersionRoot<PythonTestDescriptionArg> {

  private static final Flavor BINARY_FLAVOR = InternalFlavor.of("binary");

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));

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
  public Class<PythonTestDescriptionArg> getConstructorArgType() {
    return PythonTestDescriptionArg.class;
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
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "%s").resolve(getTestModulesListName());
  }

  /**
   * Create the contents of a python source file that just contains a list of the given test
   * modules.
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
   * Return a {@link BuildRule} that constructs the source file which contains the list of test
   * modules this python test rule will run. Setting up a separate build rule for this allows us to
   * use the existing python binary rule without changes to account for the build-time creation of
   * this file.
   */
  private static BuildRule createTestModulesSourceBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Path outputPath,
      ImmutableSet<String> testModules) {

    // Modify the build rule params to change the target, type, and remove all deps.
    buildTarget.checkUnflavored();
    BuildTarget newBuildTarget = buildTarget.withAppendedFlavors(InternalFlavor.of("test_module"));

    String contents = getTestModulesListContents(testModules);

    return new WriteFile(
        newBuildTarget,
        projectFilesystem,
        params.withoutDeclaredDeps().withoutExtraDeps(),
        contents,
        outputPath, /* executable */
        false);
  }

  private CxxPlatform getCxxPlatform(BuildTarget target, AbstractPythonTestDescriptionArg args) {
    return cxxPlatforms
        .getValue(target)
        .orElse(args.getCxxPlatform().map(cxxPlatforms::getValue).orElse(defaultCxxPlatform));
  }

  @Override
  public PythonTest createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final PythonTestDescriptionArg args) {

    PythonPlatform pythonPlatform =
        pythonPlatforms
            .getValue(buildTarget)
            .orElse(
                pythonPlatforms.getValue(
                    args.getPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElse(pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform = getCxxPlatform(buildTarget, args);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    Path baseModule = PythonUtil.getBasePath(buildTarget, args.getBaseModule());
    Optional<ImmutableMap<BuildTarget, Version>> selectedVersions =
        targetGraph.get(buildTarget).getSelectedVersions();

    ImmutableMap<Path, SourcePath> srcs =
        PythonUtil.getModules(
            buildTarget,
            resolver,
            ruleFinder,
            pathResolver,
            pythonPlatform,
            cxxPlatform,
            "srcs",
            baseModule,
            args.getSrcs(),
            args.getPlatformSrcs(),
            args.getVersionedSrcs(),
            selectedVersions);

    ImmutableMap<Path, SourcePath> resources =
        PythonUtil.getModules(
            buildTarget,
            resolver,
            ruleFinder,
            pathResolver,
            pythonPlatform,
            cxxPlatform,
            "resources",
            baseModule,
            args.getResources(),
            args.getPlatformResources(),
            args.getVersionedResources(),
            selectedVersions);

    // Convert the passed in module paths into test module names.
    ImmutableSet.Builder<String> testModulesBuilder = ImmutableSet.builder();
    for (Path name : srcs.keySet()) {
      testModulesBuilder.add(PythonUtil.toModuleName(buildTarget, name.toString()));
    }
    ImmutableSet<String> testModules = testModulesBuilder.build();

    // Construct a build rule to generate the test modules list source file and
    // add it to the build.
    BuildRule testModulesBuildRule =
        createTestModulesSourceBuildRule(
            buildTarget,
            projectFilesystem,
            params,
            getTestModulesListPath(buildTarget, projectFilesystem),
            testModules);
    resolver.addToIndex(testModulesBuildRule);

    String mainModule;
    if (args.getMainModule().isPresent()) {
      mainModule = args.getMainModule().get();
    } else {
      mainModule = PythonUtil.toModuleName(buildTarget, getTestMainName().toString());
    }

    // Build up the list of everything going into the python test.
    PythonPackageComponents testComponents =
        PythonPackageComponents.of(
            ImmutableMap.<Path, SourcePath>builder()
                .put(getTestModulesListName(), testModulesBuildRule.getSourcePathToOutput())
                .put(getTestMainName(), pythonBuckConfig.getPathToTestMain(projectFilesystem))
                .putAll(srcs)
                .build(),
            resources,
            ImmutableMap.of(),
            ImmutableSet.of(),
            args.getZipSafe());
    ImmutableList<BuildRule> deps =
        RichStream.from(
                PythonUtil.getDeps(
                    pythonPlatform, cxxPlatform, args.getDeps(), args.getPlatformDeps()))
            .concat(args.getNeededCoverage().stream().map(NeededCoverageSpec::getBuildTarget))
            .map(resolver::getRule)
            .collect(MoreCollectors.toImmutableList());
    PythonPackageComponents allComponents =
        PythonUtil.getAllComponents(
            buildTarget,
            projectFilesystem,
            params,
            resolver,
            ruleFinder,
            deps,
            testComponents,
            pythonPlatform,
            cxxBuckConfig,
            cxxPlatform,
            args.getLinkerFlags()
                .stream()
                .map(
                    MacroArg.toMacroArgFunction(
                        PythonUtil.MACRO_HANDLER, buildTarget, cellRoots, resolver))
                .collect(MoreCollectors.toImmutableList()),
            pythonBuckConfig.getNativeLinkStrategy(),
            args.getPreloadDeps());

    // Build the PEX using a python binary rule with the minimum dependencies.
    buildTarget.checkUnflavored();
    PythonBinary binary =
        binaryDescription.createPackageRule(
            buildTarget.withAppendedFlavors(BINARY_FLAVOR),
            projectFilesystem,
            params,
            resolver,
            ruleFinder,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            args.getExtension(),
            allComponents,
            args.getBuildArgs(),
            args.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle()),
            PythonUtil.getPreloadNames(resolver, cxxPlatform, args.getPreloadDeps()));
    resolver.addToIndex(binary);

    ImmutableList.Builder<Pair<Float, ImmutableSet<Path>>> neededCoverageBuilder =
        ImmutableList.builder();
    for (NeededCoverageSpec coverageSpec : args.getNeededCoverage()) {
      BuildRule buildRule = resolver.getRule(coverageSpec.getBuildTarget());
      if (deps.contains(buildRule) && buildRule instanceof PythonLibrary) {
        PythonLibrary pythonLibrary = (PythonLibrary) buildRule;
        ImmutableSortedSet<Path> paths;
        if (coverageSpec.getPathName().isPresent()) {
          Path path =
              coverageSpec.getBuildTarget().getBasePath().resolve(coverageSpec.getPathName().get());
          if (!pythonLibrary
              .getPythonPackageComponents(pythonPlatform, cxxPlatform)
              .getModules()
              .keySet()
              .contains(path)) {
            throw new HumanReadableException(
                "%s: path %s specified in needed_coverage not found in target %s",
                buildTarget, path, buildRule.getBuildTarget());
          }
          paths = ImmutableSortedSet.of(path);
        } else {
          paths =
              ImmutableSortedSet.copyOf(
                  pythonLibrary
                      .getPythonPackageComponents(pythonPlatform, cxxPlatform)
                      .getModules()
                      .keySet());
        }
        neededCoverageBuilder.add(
            new Pair<Float, ImmutableSet<Path>>(coverageSpec.getNeededCoverageRatio(), paths));
      } else {
        throw new HumanReadableException(
            "%s: needed_coverage requires a python library dependency. Found %s instead",
            buildTarget, buildRule);
      }
    }

    Supplier<ImmutableMap<String, String>> testEnv =
        () ->
            ImmutableMap.copyOf(
                Maps.transformValues(
                    args.getEnv(),
                    MACRO_HANDLER.getExpander(buildTarget, cellRoots, resolver)::apply));

    // Generate and return the python test rule, which depends on the python binary rule above.
    return PythonTest.from(
        buildTarget,
        projectFilesystem,
        params,
        testEnv,
        binary,
        args.getLabels(),
        neededCoverageBuilder.build(),
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getContacts());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractPythonTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // We need to use the C/C++ linker for native libs handling, so add in the C/C++ linker to
    // parse time deps.
    extraDepsBuilder.addAll(getCxxPlatform(buildTarget, constructorArg).getLd().getParseTimeDeps());

    if (constructorArg.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle())
        == PythonBuckConfig.PackageStyle.STANDALONE) {
      Optionals.addIfPresent(pythonBuckConfig.getPexTarget(), extraDepsBuilder);
      Optionals.addIfPresent(pythonBuckConfig.getPexExecutorTarget(), extraDepsBuilder);
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPythonTestDescriptionArg
      extends HasContacts, HasTestTimeout, PythonLibraryDescription.CoreArg, HasVersionUniverse {
    Optional<String> getMainModule();

    Optional<String> getPlatform();

    Optional<Flavor> getCxxPlatform();

    Optional<String> getExtension();

    Optional<PythonBuckConfig.PackageStyle> getPackageStyle();

    ImmutableSet<BuildTarget> getPreloadDeps();

    ImmutableList<String> getLinkerFlags();

    ImmutableList<NeededCoverageSpec> getNeededCoverage();

    ImmutableList<String> getBuildArgs();

    ImmutableMap<String, String> getEnv();
  }
}

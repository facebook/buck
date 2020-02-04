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

package com.facebook.buck.features.python;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.test.rule.HasTestRunner;
import com.facebook.buck.core.test.rule.coercer.TestRunnerSpecCoercer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.NeededCoverageSpec;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.Resources;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;

public class PythonTestDescription
    implements DescriptionWithTargetGraph<PythonTestDescriptionArg>,
        ImplicitDepsInferringDescription<PythonTestDescription.AbstractPythonTestDescriptionArg>,
        VersionRoot<PythonTestDescriptionArg> {

  public static final Flavor BINARY_FLAVOR = InternalFlavor.of("binary");
  private static final String DEFAULT_TEST_MAIN_NAME = "__test_main__.py";

  private final ToolchainProvider toolchainProvider;
  private final PythonBinaryDescription binaryDescription;
  private final PythonBuckConfig pythonBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;

  public PythonTestDescription(
      ToolchainProvider toolchainProvider,
      PythonBinaryDescription binaryDescription,
      PythonBuckConfig pythonBuckConfig,
      CxxBuckConfig cxxBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.binaryDescription = binaryDescription;
    this.pythonBuckConfig = pythonBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
  }

  @Override
  public Class<PythonTestDescriptionArg> getConstructorArgType() {
    return PythonTestDescriptionArg.class;
  }

  @VisibleForTesting
  protected static Path getTestMainPath(
      SourcePathResolverAdapter resolver, Optional<PythonTestRunner> testRunner) {
    return testRunner
        .map(runner -> resolver.getRelativePath(runner.getSrc()))
        .orElse(Paths.get(DEFAULT_TEST_MAIN_NAME));
  }

  @VisibleForTesting
  protected static Path getTestModulesListName() {
    return Paths.get("__test_modules__.py");
  }

  @VisibleForTesting
  protected static Path getTestModulesListPath(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s")
        .resolve(getTestModulesListName());
  }

  /**
   * Create the contents of a python source file that just contains a list of the given test
   * modules.
   */
  private static String getTestModulesListContents(ImmutableSet<String> modules) {
    StringBuilder contents = new StringBuilder("TEST_MODULES = [\n");
    for (String module : modules) {
      contents.append(String.format("    \"%s\",\n", module));
    }
    contents.append("]");
    return contents.toString();
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
      Path outputPath,
      ImmutableSet<String> testModules) {

    // Modify the build rule params to change the target, type, and remove all deps.
    buildTarget.assertUnflavored();
    BuildTarget newBuildTarget = buildTarget.withAppendedFlavors(InternalFlavor.of("test_module"));

    String contents = getTestModulesListContents(testModules);

    return new WriteFile(
        newBuildTarget, projectFilesystem, contents, outputPath, /* executable */ false);
  }

  private UnresolvedCxxPlatform getCxxPlatform(
      BuildTarget target, AbstractPythonTestDescriptionArg args) {
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            target.getTargetConfiguration(),
            CxxPlatformsProvider.class);
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms();

    return cxxPlatforms
        .getValue(target)
        .orElse(
            args.getCxxPlatform()
                .map(cxxPlatforms::getValue)
                .orElse(cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform()));
  }

  /**
   * Build rule for Python test that does not adhere to the TestX protocol. Hardcodes the path to a
   * test runner.
   */
  private static class PythonTestMainRule extends AbstractBuildRule {
    private final Path output =
        BuildTargetPaths.getGenPath(
            getProjectFilesystem(), getBuildTarget(), "%s/" + DEFAULT_TEST_MAIN_NAME);

    public PythonTestMainRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
      super(buildTarget, projectFilesystem);
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableList<? extends Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      buildableContext.recordArtifact(output);
      return ImmutableList.of(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())),
          new WriteFileStep(
              getProjectFilesystem(),
              Resources.asByteSource(
                  Resources.getResource(PythonTestDescription.class, DEFAULT_TEST_MAIN_NAME)),
              output,
              /* executable */ false));
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
    }
  }

  private SourcePath requireTestMain(
      BuildTarget baseTarget, ProjectFilesystem filesystem, ActionGraphBuilder graphBuilder) {
    BuildRule testMainRule =
        graphBuilder.computeIfAbsent(
            baseTarget.withFlavors(InternalFlavor.of("python-test-main")),
            target -> new PythonTestMainRule(target, filesystem));
    return Objects.requireNonNull(testMainRule.getSourcePathToOutput());
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PythonTestDescriptionArg args) {

    FlavorDomain<PythonPlatform> pythonPlatforms =
        toolchainProvider
            .getByName(
                PythonPlatformsProvider.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                PythonPlatformsProvider.class)
            .getPythonPlatforms();

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    PythonPlatform pythonPlatform =
        pythonPlatforms
            .getValue(buildTarget)
            .orElse(
                pythonPlatforms.getValue(
                    args.getPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElse(pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform =
        getCxxPlatform(buildTarget, args)
            .resolve(graphBuilder, buildTarget.getTargetConfiguration());
    Optional<ImmutableMap<BuildTarget, Version>> selectedVersions =
        context.getTargetGraph().get(buildTarget).getSelectedVersions();

    ImmutableMap<Path, SourcePath> srcs =
        PythonUtil.parseModules(
            buildTarget, graphBuilder, pythonPlatform, cxxPlatform, selectedVersions, args);

    ImmutableMap<Path, SourcePath> resources =
        PythonUtil.parseResources(
            buildTarget, graphBuilder, pythonPlatform, cxxPlatform, selectedVersions, args);

    // Convert the passed in module paths into test module names.
    ImmutableSet.Builder<String> testModulesBuilder = ImmutableSet.builder();
    for (Path name : srcs.keySet()) {
      testModulesBuilder.add(PythonUtil.toModuleName(buildTarget, name.toString()));
    }
    ImmutableSet<String> testModules = testModulesBuilder.build();

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // Construct a build rule to generate the test modules list source file and
    // add it to the build.
    BuildRule testModulesBuildRule =
        createTestModulesSourceBuildRule(
            buildTarget,
            projectFilesystem,
            getTestModulesListPath(buildTarget, projectFilesystem),
            testModules);
    graphBuilder.addToIndex(testModulesBuildRule);

    Optional<PythonTestRunner> testRunner = maybeGetTestRunner(args, graphBuilder);
    Path testMainName = getTestMainPath(graphBuilder.getSourcePathResolver(), testRunner);
    String mainModule =
        testRunner
            .map(runner -> runner.getMainModule())
            .orElse(
                args.getMainModule()
                    .orElseGet(
                        () -> PythonUtil.toModuleName(buildTarget, testMainName.toString())));

    ImmutableSortedMap<Path, SourcePath> modules =
        ImmutableSortedMap.<Path, SourcePath>naturalOrder()
            .put(getTestModulesListName(), testModulesBuildRule.getSourcePathToOutput())
            .put(
                testMainName,
                testRunner
                    .map(runner -> runner.getSrc())
                    .orElseGet(() -> requireTestMain(buildTarget, projectFilesystem, graphBuilder)))
            .putAll(srcs)
            .build();

    ImmutableList<BuildRule> deps =
        RichStream.from(
                PythonUtil.getDeps(
                    pythonPlatform, cxxPlatform, args.getDeps(), args.getPlatformDeps()))
            .concat(args.getNeededCoverage().stream().map(NeededCoverageSpec::getBuildTarget))
            .map(graphBuilder::getRule)
            .collect(ImmutableList.toImmutableList());

    // Build up the list of everything going into the python test.
    PythonPackagable root =
        ImmutablePythonBinaryPackagable.of(
            buildTarget,
            projectFilesystem,
            deps,
            Optional.of(PythonMappedComponents.of(modules)),
            Optional.of(PythonMappedComponents.of(ImmutableSortedMap.copyOf(resources))),
            args.getZipSafe());

    CellPathResolver cellRoots = context.getCellPathResolver();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            cellRoots.getCellNameResolver(),
            graphBuilder,
            PythonUtil.macroExpanders(context.getTargetGraph()));
    PythonPackageComponents allComponents =
        PythonUtil.getAllComponents(
            cellRoots,
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            root,
            pythonPlatform,
            cxxBuckConfig,
            cxxPlatform,
            args.getLinkerFlags().stream()
                .map(macrosConverter::convert)
                .collect(ImmutableList.toImmutableList()),
            pythonBuckConfig.getNativeLinkStrategy(),
            args.getPreloadDeps(),
            args.getCompile().orElse(false));

    // Build the PEX using a python binary rule with the minimum dependencies.
    buildTarget.assertUnflavored();
    PythonBinary binary =
        binaryDescription.createPackageRule(
            cellRoots,
            buildTarget.withAppendedFlavors(BINARY_FLAVOR),
            projectFilesystem,
            params,
            graphBuilder,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            args.getExtension(),
            allComponents,
            args.getBuildArgs(),
            args.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle()),
            PythonUtil.getPreloadNames(graphBuilder, cxxPlatform, args.getPreloadDeps()));
    graphBuilder.addToIndex(binary);

    if (testRunner.isPresent()) {
      Preconditions.checkState(
          args.getSpecs().isPresent(), "Specs must be present when runner is present.");
      return PythonTestX.from(
          buildTarget,
          projectFilesystem,
          params,
          binary,
          args.getLabels(),
          args.getContacts(),
          TestRunnerSpecCoercer.coerce(args.getSpecs().get(), macrosConverter));
    }

    ImmutableList.Builder<Pair<Float, ImmutableSet<Path>>> neededCoverageBuilder =
        ImmutableList.builder();
    for (NeededCoverageSpec coverageSpec : args.getNeededCoverage()) {
      BuildRule buildRule = graphBuilder.getRule(coverageSpec.getBuildTarget());
      if (deps.contains(buildRule) && buildRule instanceof PythonLibrary) {
        PythonLibrary pythonLibrary = (PythonLibrary) buildRule;
        ImmutableSortedSet<Path> paths;
        if (coverageSpec.getPathName().isPresent()) {
          Path path =
              coverageSpec
                  .getBuildTarget()
                  .getCellRelativeBasePath()
                  .getPath()
                  .toPath(projectFilesystem.getFileSystem())
                  .resolve(coverageSpec.getPathName().get());
          if (!pythonLibrary
              .getPythonModules(pythonPlatform, cxxPlatform, graphBuilder)
              .map(PythonMappedComponents::getComponents)
              .map(Map::keySet)
              .orElseGet(ImmutableSet::of)
              .contains(path)) {
            throw new HumanReadableException(
                "%s: path %s specified in needed_coverage not found in target %s",
                buildTarget, path, buildRule.getBuildTarget());
          }
          paths = ImmutableSortedSet.of(path);
        } else {
          paths =
              pythonLibrary
                  .getPythonModules(pythonPlatform, cxxPlatform, graphBuilder)
                  .map(PythonMappedComponents::getComponents)
                  .map(ImmutableSortedMap::keySet)
                  .orElseGet(ImmutableSortedSet::of);
        }
        neededCoverageBuilder.add(
            new Pair<>(coverageSpec.getNeededCoverageRatioPercentage() / 100.f, paths));
      } else {
        throw new HumanReadableException(
            "%s: needed_coverage requires a python library dependency. Found %s instead",
            buildTarget, buildRule);
      }
    }

    Function<BuildRuleResolver, ImmutableMap<String, Arg>> testEnv =
        (ruleResolverInner) ->
            ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert));

    // Additional CXX Targets used to generate CXX coverage.
    ImmutableSet<UnflavoredBuildTarget> additionalCoverageTargets =
        RichStream.from(args.getAdditionalCoverageTargets())
            .map(BuildTarget::getUnflavoredBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableSortedSet<SourcePath> additionalCoverageSourcePaths =
        additionalCoverageTargets.isEmpty()
            ? ImmutableSortedSet.of()
            : binary
                .getRuntimeDeps(graphBuilder)
                .filter(
                    target -> additionalCoverageTargets.contains(target.getUnflavoredBuildTarget()))
                .map(DefaultBuildTargetSourcePath::of)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));

    // Generate and return the python test rule, which depends on the python binary rule above.
    return PythonTest.from(
        buildTarget,
        projectFilesystem,
        params,
        graphBuilder,
        testEnv,
        binary,
        args.getLabels(),
        neededCoverageBuilder.build(),
        additionalCoverageSourcePaths,
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(
                cxxBuckConfig
                    .getDelegate()
                    .getView(TestBuckConfig.class)
                    .getDefaultTestRuleTimeoutMs()),
        args.getContacts());
  }

  private Optional<PythonTestRunner> maybeGetTestRunner(
      PythonTestDescriptionArg args, ActionGraphBuilder graphBuilder) {
    if (args.getRunner().isPresent()) {
      BuildRule runnerRule = graphBuilder.requireRule(args.getRunner().get());
      Preconditions.checkState(
          runnerRule instanceof PythonTestRunner,
          "Python tests should have python_test_runner as the test protocol runner.");
      return Optional.of((PythonTestRunner) runnerRule);
    }
    return Optional.empty();
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractPythonTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // We need to use the C/C++ linker for native libs handling, so add in the C/C++ linker to
    // parse time deps.
    extraDepsBuilder.addAll(
        getCxxPlatform(buildTarget, constructorArg)
            .getLinkerParseTimeDeps(buildTarget.getTargetConfiguration()));

    if (constructorArg.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle())
        == PythonBuckConfig.PackageStyle.STANDALONE) {
      Optionals.addIfPresent(
          pythonBuckConfig.getPexTarget(buildTarget.getTargetConfiguration()), extraDepsBuilder);
      Optionals.addIfPresent(
          pythonBuckConfig.getPexExecutorTarget(buildTarget.getTargetConfiguration()),
          extraDepsBuilder);
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @RuleArg
  interface AbstractPythonTestDescriptionArg
      extends HasContacts,
          HasTestRunner,
          HasTestTimeout,
          PythonLibraryDescription.CoreArg,
          HasVersionUniverse {
    Optional<String> getMainModule();

    Optional<String> getPlatform();

    Optional<Flavor> getCxxPlatform();

    Optional<String> getExtension();

    Optional<PythonBuckConfig.PackageStyle> getPackageStyle();

    ImmutableSet<BuildTarget> getPreloadDeps();

    ImmutableList<StringWithMacros> getLinkerFlags();

    ImmutableList<NeededCoverageSpec> getNeededCoverage();

    ImmutableList<StringWithMacros> getBuildArgs();

    ImmutableMap<String, StringWithMacros> getEnv();

    // Additional CxxLibrary Targets for coverage check
    // When we use python to drive cxx modules (loaded as foo.so), we would like
    // to collect code coverage of foo.so as well. In this case, we to path
    // targets that builds foo.so so that buck can resolve its binary path and
    // export the downstream testing framework to consume
    ImmutableSet<BuildTarget> getAdditionalCoverageTargets();

    Optional<Boolean> getCompile();
  }
}

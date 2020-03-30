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

package com.facebook.buck.features.go;

import com.facebook.buck.cli.BuildCommand;
import com.facebook.buck.cli.CommandRunnerParams;
import com.facebook.buck.cli.ProjectGeneratorParameters;
import com.facebook.buck.cli.ProjectTestsMode;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.features.go.CgoLibraryDescription.AbstractCgoLibraryDescriptionArg;
import com.facebook.buck.features.go.GoLibraryDescription.AbstractGoLibraryDescriptionArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.collect.MoreSets;
import com.facebook.buck.versions.VersionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;

public class GoProjectCommandHelper {

  private final CommandRunnerParams params;
  private final BuckEventBus buckEventBus;
  private final Console console;
  private final Parser parser;
  private final GoBuckConfig goBuckConfig;
  private final BuckConfig buckConfig;
  private final Cells cells;
  private final Optional<TargetConfiguration> targetConfiguration;
  private final Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser;
  private final ParsingContext parsingContext;

  private final ProjectGeneratorParameters projectGeneratorParameters;

  public GoProjectCommandHelper(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      boolean enableParserProfiling,
      Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser,
      ProjectGeneratorParameters projectGeneratorParameters,
      Optional<TargetConfiguration> targetConfiguration) {
    this.params = params;
    this.buckEventBus = params.getBuckEventBus();
    this.console = projectGeneratorParameters.getConsole();
    this.parser = projectGeneratorParameters.getParser();
    this.goBuckConfig = new GoBuckConfig(params.getBuckConfig());
    this.buckConfig = params.getBuckConfig();
    this.cells = params.getCells();
    this.argsParser = argsParser;
    this.projectGeneratorParameters = projectGeneratorParameters;
    this.targetConfiguration = targetConfiguration;
    this.parsingContext =
        ParsingContext.builder(cells.getRootCell(), executor)
            .setProfilingEnabled(enableParserProfiling)
            .setSpeculativeParsing(SpeculativeParsing.ENABLED)
            .setApplyDefaultFlavorsMode(
                buckConfig.getView(ParserConfig.class).getDefaultFlavorsMode())
            .build();
  }

  public ExitCode parseTargetsAndRunProjectGenerator(List<String> arguments) throws Exception {
    List<String> targets = arguments;
    if (targets.isEmpty()) {
      targets = ImmutableList.of("//...");
    }

    TargetGraphCreationResult targetGraphCreationResult;

    try {
      ImmutableSet<BuildTarget> passedInTargetsSet =
          ImmutableSet.copyOf(
              Iterables.concat(
                  parser.resolveTargetSpecs(
                      parsingContext, argsParser.apply(targets), targetConfiguration)));
      if (passedInTargetsSet.isEmpty()) {
        throw new HumanReadableException("Could not find targets matching arguments");
      }
      targetGraphCreationResult = parser.buildTargetGraph(parsingContext, passedInTargetsSet);
    } catch (BuildFileParseException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    try {
      targetGraphCreationResult =
          enhanceTargetGraphIfNeeded(
              params.getDepsAwareExecutorSupplier().get(), targetGraphCreationResult);
    } catch (BuildFileParseException | NoSuchTargetException | VersionException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    if (projectGeneratorParameters.isDryRun()) {
      for (TargetNode<?> targetNode : targetGraphCreationResult.getTargetGraph().getNodes()) {
        console.getStdOut().println(targetNode.toString());
      }

      return ExitCode.SUCCESS;
    }
    return initGoWorkspace(targetGraphCreationResult);
  }

  private ActionGraphAndBuilder getActionGraph(TargetGraphCreationResult targetGraph) {
    return params.getActionGraphProvider().getActionGraph(targetGraph);
  }

  /**
   * Instead of specifying the location of libraries in project files, Go requires libraries to be
   * in locations consistent with their package name, either relative to GOPATH environment variable
   * or to the "vendor" folder of a project. This method identifies code generation targets, builds
   * them, and copy the generated code from buck-out to vendor, so that they are accessible by IDEs.
   */
  private ExitCode initGoWorkspace(TargetGraphCreationResult targetGraphCreationResult)
      throws Exception {
    Map<BuildTargetSourcePath, Path> generatedPackages =
        findCodeGenerationTargets(targetGraphCreationResult.getTargetGraph());
    if (generatedPackages.isEmpty()) {
      return ExitCode.SUCCESS;
    }
    // Run code generation targets
    ExitCode exitCode =
        runBuild(
            generatedPackages.keySet().stream()
                .map(BuildTargetSourcePath::getTarget)
                .collect(ImmutableSet.toImmutableSet()));
    if (exitCode != ExitCode.SUCCESS) {
      return exitCode;
    }

    copyGeneratedGoCode(targetGraphCreationResult, generatedPackages);
    return ExitCode.SUCCESS;
  }

  /**
   * Assuming GOPATH is set to a directory higher or equal to buck root, copy generated code to the
   * package path relative to the highest level vendor directory. Not handling the case of GOPATH
   * lower than buck root for now, as it requires walking the directory structure, which can be
   * expensive and unreliable (e.g., what if there are multiple src directory?).
   */
  private void copyGeneratedGoCode(
      TargetGraphCreationResult targetGraphCreationResult,
      Map<BuildTargetSourcePath, Path> generatedPackages)
      throws IOException {
    Path vendorPath;
    ProjectFilesystem projectFilesystem = cells.getRootCell().getFilesystem();

    Optional<Path> projectPath = goBuckConfig.getProjectPath();
    if (projectPath.isPresent()) {
      vendorPath = projectPath.get();
    } else if (projectFilesystem.exists(Paths.get("src"))) {
      vendorPath = Paths.get("src", "vendor");
    } else {
      vendorPath = Paths.get("vendor");
    }
    ActionGraphAndBuilder result =
        Objects.requireNonNull(getActionGraph(targetGraphCreationResult));

    // cleanup files from previous runs
    for (BuildTargetSourcePath sourcePath : generatedPackages.keySet()) {
      Path desiredPath = vendorPath.resolve(generatedPackages.get(sourcePath));

      if (projectFilesystem.isDirectory(desiredPath)) {
        for (Path path : projectFilesystem.getDirectoryContents(desiredPath)) {
          if (projectFilesystem.isFile(path)) {
            projectFilesystem.deleteFileAtPath(path);
          }
        }
      } else {
        projectFilesystem.mkdirs(desiredPath);
      }
    }

    // copy files generated in current run
    for (BuildTargetSourcePath sourcePath : generatedPackages.keySet()) {
      Path desiredPath = vendorPath.resolve(generatedPackages.get(sourcePath));
      Path generatedSrc =
          result.getActionGraphBuilder().getSourcePathResolver().getAbsolutePath(sourcePath);

      if (projectFilesystem.isDirectory(generatedSrc)) {
        projectFilesystem.copyFolder(generatedSrc, desiredPath);
      } else {
        projectFilesystem.copyFile(generatedSrc, desiredPath.resolve(generatedSrc.getFileName()));
      }
    }
  }

  /**
   * Find code generation targets by inspecting go_library and cgo_library targets in the target
   * graph with "srcs", "go_srcs", or "headers" pointing to other Buck targets rather than regular
   * files. Those Buck targets are assumed to be code generation targets. Their output is intended
   * to be used as some package name, either specified by package_name argument of go_library or
   * cgo_library, or guessed from the base path of the targets. For cgo_library targets, this method
   * also examine its cxxDeps and see if any of the cxx_library targets has empty header_namespace,
   * which indicates that the cxx_library is in the same package as the cgo_library. In such case,
   * the srcs and headers of the cxx_library that are Buck targets are also copied.
   */
  private Map<BuildTargetSourcePath, Path> findCodeGenerationTargets(TargetGraph targetGraph) {
    Map<BuildTargetSourcePath, Path> generatedPackages = new HashMap<>();
    for (TargetNode<?> targetNode : targetGraph.getNodes()) {
      Object constructorArg = targetNode.getConstructorArg();
      BuildTarget buildTarget = targetNode.getBuildTarget();
      if (constructorArg instanceof AbstractGoLibraryDescriptionArg) {
        AbstractGoLibraryDescriptionArg goArgs = (AbstractGoLibraryDescriptionArg) constructorArg;
        Optional<String> packageNameArg = goArgs.getPackageName();
        Path pkgName =
            packageNameArg.map(Paths::get).orElse(goBuckConfig.getDefaultPackageName(buildTarget));
        generatedPackages.putAll(getSrcsMap(filterBuildTargets(goArgs.getSrcs()), pkgName));
      } else if (constructorArg instanceof AbstractCgoLibraryDescriptionArg) {
        AbstractCgoLibraryDescriptionArg cgoArgs =
            (AbstractCgoLibraryDescriptionArg) constructorArg;
        Optional<String> packageNameArg = cgoArgs.getPackageName();
        Path pkgName =
            packageNameArg.map(Paths::get).orElse(goBuckConfig.getDefaultPackageName(buildTarget));
        generatedPackages.putAll(getSrcsMap(getSrcAndHeaderTargets(cgoArgs), pkgName));
        generatedPackages.putAll(getSrcsMap(filterBuildTargets(cgoArgs.getGoSrcs()), pkgName));
        List<CxxConstructorArg> cxxLibs =
            cgoArgs.getCxxDeps().getDeps().stream()
                .filter(
                    target ->
                        targetGraph.get(target).getConstructorArg() instanceof CxxConstructorArg)
                .map(target -> (CxxConstructorArg) targetGraph.get(target).getConstructorArg())
                .filter(
                    cxxArgs -> cxxArgs.getHeaderNamespace().filter(ns -> ns.equals("")).isPresent())
                .collect(Collectors.toList());
        for (CxxConstructorArg cxxArgs : cxxLibs) {
          generatedPackages.putAll(getSrcsMap(getSrcAndHeaderTargets(cxxArgs), pkgName));
        }
      }
    }
    return generatedPackages;
  }

  @Nonnull
  private Map<BuildTargetSourcePath, Path> getSrcsMap(
      Stream<BuildTargetSourcePath> targetPaths, Path pkgName) {
    return targetPaths.collect(Collectors.toMap(src -> src, src -> pkgName));
  }

  private Stream<BuildTargetSourcePath> getSrcAndHeaderTargets(CxxConstructorArg constructorArg) {
    List<BuildTargetSourcePath> targets = new ArrayList<>();
    targets.addAll(
        filterBuildTargets(
                constructorArg.getSrcs().stream()
                    .map(srcWithFlags -> srcWithFlags.getSourcePath())
                    .collect(Collectors.toSet()))
            .collect(Collectors.toList()));
    constructorArg
        .getHeaders()
        .getUnnamedSources()
        .ifPresent(
            headers -> targets.addAll(filterBuildTargets(headers).collect(Collectors.toList())));
    return targets.stream();
  }

  @Nonnull
  private Stream<BuildTargetSourcePath> filterBuildTargets(Set<SourcePath> paths) {
    return paths.stream()
        .filter(srcPath -> srcPath instanceof BuildTargetSourcePath)
        .map(src -> (BuildTargetSourcePath) src);
  }

  private ProjectTestsMode testsMode() {
    ProjectTestsMode parameterMode = ProjectTestsMode.WITH_TESTS;

    if (projectGeneratorParameters.isWithoutTests()) {
      parameterMode = ProjectTestsMode.WITHOUT_TESTS;
    } else if (projectGeneratorParameters.isWithoutDependenciesTests()) {
      parameterMode = ProjectTestsMode.WITHOUT_DEPENDENCIES_TESTS;
    } else if (projectGeneratorParameters.isWithTests()) {
      parameterMode = ProjectTestsMode.WITH_TESTS;
    }

    return parameterMode;
  }

  private boolean isWithTests() {
    return testsMode() != ProjectTestsMode.WITHOUT_TESTS;
  }

  private boolean isWithDependenciesTests() {
    return testsMode() == ProjectTestsMode.WITH_TESTS;
  }

  private TargetGraphCreationResult enhanceTargetGraphIfNeeded(
      DepsAwareExecutor<? super ComputeResult, ?> depsAwareExecutor,
      TargetGraphCreationResult targetGraphCreationResult)
      throws IOException, InterruptedException, BuildFileParseException, VersionException {

    TargetGraph projectGraph = targetGraphCreationResult.getTargetGraph();
    ImmutableSet<BuildTarget> graphRoots = targetGraphCreationResult.getBuildTargets();

    if (isWithTests()) {
      targetGraphCreationResult =
          parser.buildTargetGraph(
              parsingContext,
              MoreSets.union(graphRoots, getExplicitTestTargets(graphRoots, projectGraph)));
    }

    if (buckConfig.getView(BuildBuckConfig.class).getBuildVersions()) {
      targetGraphCreationResult =
          params
              .getVersionedTargetGraphCache()
              .toVersionedTargetGraph(
                  depsAwareExecutor,
                  buckConfig,
                  params.getTypeCoercerFactory(),
                  params.getUnconfiguredBuildTargetFactory(),
                  targetGraphCreationResult,
                  targetConfiguration,
                  buckEventBus,
                  cells);
    }
    return targetGraphCreationResult;
  }

  /**
   * @param buildTargets The set of targets for which we would like to find tests
   * @param projectGraph A TargetGraph containing all nodes and their tests.
   * @return A set of all test targets that test any of {@code buildTargets} or their dependencies.
   */
  private ImmutableSet<BuildTarget> getExplicitTestTargets(
      ImmutableSet<BuildTarget> buildTargets, TargetGraph projectGraph) {
    Iterable<TargetNode<?>> projectRoots = projectGraph.getAll(buildTargets);
    Iterable<TargetNode<?>> nodes;
    if (isWithDependenciesTests()) {
      nodes = projectGraph.getSubgraph(projectRoots).getNodes();
    } else {
      nodes = projectRoots;
    }
    return TargetNodes.getTestTargetsForNodes(nodes.iterator());
  }

  private ExitCode runBuild(ImmutableSet<BuildTarget> targets) throws Exception {
    BuildCommand buildCommand =
        new BuildCommand(
            targets.stream().map(Object::toString).collect(ImmutableList.toImmutableList()));
    buildCommand.setKeepGoing(true);
    return buildCommand.run(params);
  }
}

/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.cli.BuildCommand;
import com.facebook.buck.cli.CommandRunnerParams;
import com.facebook.buck.cli.ProjectTestsMode;
import com.facebook.buck.cli.parameter_extractors.ProjectGeneratorParameters;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphAndTargets;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.versions.VersionException;
import com.facebook.buck.versions.VersionedTargetGraphAndTargets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GoProjectCommandHelper {

  private final CommandRunnerParams params;
  private final BuckEventBus buckEventBus;
  private final Console console;
  private final ListeningExecutorService executor;
  private final Parser parser;
  private final GoBuckConfig goBuckConfig;
  private final BuckConfig buckConfig;
  private final Cell cell;
  private final boolean enableParserProfiling;
  private final Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser;

  private final ProjectGeneratorParameters projectGeneratorParameters;

  public GoProjectCommandHelper(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      boolean enableParserProfiling,
      Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser,
      ProjectGeneratorParameters projectGeneratorParameters) {
    this.params = params;
    this.buckEventBus = params.getBuckEventBus();
    this.console = projectGeneratorParameters.getConsole();
    this.executor = executor;
    this.parser = projectGeneratorParameters.getParser();
    this.goBuckConfig = new GoBuckConfig(params.getBuckConfig());
    this.buckConfig = params.getBuckConfig();
    this.cell = params.getCell();
    this.enableParserProfiling = enableParserProfiling;
    this.argsParser = argsParser;
    this.projectGeneratorParameters = projectGeneratorParameters;
  }

  public ExitCode parseTargetsAndRunProjectGenerator(List<String> arguments)
      throws IOException, InterruptedException {
    List<String> targets = arguments;
    if (targets.isEmpty()) {
      targets = ImmutableList.of("//...");
    }

    ImmutableSet<BuildTarget> passedInTargetsSet;
    TargetGraph projectGraph;

    try {
      ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
      passedInTargetsSet =
          ImmutableSet.copyOf(
              Iterables.concat(
                  parser.resolveTargetSpecs(
                      cell,
                      enableParserProfiling,
                      executor,
                      argsParser.apply(targets),
                      SpeculativeParsing.ENABLED,
                      parserConfig.getDefaultFlavorsMode())));
      projectGraph = getProjectGraphForIde(executor, passedInTargetsSet);
    } catch (BuildFileParseException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    ImmutableSet<BuildTarget> graphRoots;
    if (passedInTargetsSet.isEmpty()) {
      graphRoots =
          projectGraph
              .getNodes()
              .stream()
              .map(TargetNode::getBuildTarget)
              .collect(ImmutableSet.toImmutableSet());
    } else {
      graphRoots = passedInTargetsSet;
    }

    TargetGraphAndTargets targetGraphAndTargets;
    try {
      targetGraphAndTargets =
          createTargetGraph(projectGraph, graphRoots, passedInTargetsSet.isEmpty(), executor);
    } catch (BuildFileParseException | NoSuchTargetException | VersionException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.PARSE_ERROR;
    } catch (HumanReadableException e) {
      buckEventBus.post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return ExitCode.BUILD_ERROR;
    }

    if (projectGeneratorParameters.isDryRun()) {
      for (TargetNode<?> targetNode : targetGraphAndTargets.getTargetGraph().getNodes()) {
        console.getStdOut().println(targetNode.toString());
      }

      return ExitCode.SUCCESS;
    }
    return initGoWorkspace(targetGraphAndTargets);
  }

  private ActionGraphAndBuilder getActionGraph(TargetGraph targetGraph) {
    return params.getActionGraphProvider().getActionGraph(targetGraph);
  }

  private TargetGraph getProjectGraphForIde(
      ListeningExecutorService executor, ImmutableSet<BuildTarget> passedInTargets)
      throws InterruptedException, BuildFileParseException, IOException {

    if (passedInTargets.isEmpty()) {
      return parser
          .buildTargetGraphForTargetNodeSpecs(
              cell,
              enableParserProfiling,
              executor,
              ImmutableList.of(
                  TargetNodePredicateSpec.of(
                      BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))))
          .getTargetGraph();
    }
    return parser.buildTargetGraph(cell, enableParserProfiling, executor, passedInTargets);
  }

  /**
   * Instead of specifying the location of libraries in project files, Go requires libraries to be
   * in locations consistent with their package name, either relative to GOPATH environment variable
   * or to the "vendor" folder of a project. This method identifies code generation targets, builds
   * them, and copy the generated code from buck-out to vendor, so that they are accessible by IDEs.
   */
  private ExitCode initGoWorkspace(TargetGraphAndTargets targetGraphAndTargets)
      throws IOException, InterruptedException {
    Map<BuildTargetSourcePath, Path> generatedPackages =
        findCodeGenerationTargets(targetGraphAndTargets);
    if (generatedPackages.size() == 0) {
      return ExitCode.SUCCESS;
    }
    // Run code generation targets
    ExitCode exitCode =
        runBuild(
            generatedPackages
                .keySet()
                .stream()
                .map(BuildTargetSourcePath::getTarget)
                .collect(ImmutableSet.toImmutableSet()));
    if (exitCode != ExitCode.SUCCESS) {
      return exitCode;
    }

    copyGeneratedGoCode(targetGraphAndTargets, generatedPackages);
    return ExitCode.SUCCESS;
  }

  /**
   * Assuming GOPATH is set to a directory higher or equal to buck root, copy generated code to the
   * package path relative to the highest level vendor directory. Not handling the case of GOPATH
   * lower than buck root for now, as it requires walking the directory structure, which can be
   * expensive and unreliable (e.g., what if there are multiple src directory?).
   */
  private void copyGeneratedGoCode(
      TargetGraphAndTargets targetGraphAndTargets,
      Map<BuildTargetSourcePath, Path> generatedPackages)
      throws IOException {
    Path vendorPath;
    ProjectFilesystem projectFilesystem = cell.getFilesystem();

    Optional<Path> projectPath = goBuckConfig.getProjectPath();
    if (projectPath.isPresent()) {
      vendorPath = projectPath.get();
    } else if (projectFilesystem.exists(Paths.get("src"))) {
      vendorPath = Paths.get("src", "vendor");
    } else {
      vendorPath = Paths.get("vendor");
    }
    ActionGraphAndBuilder result =
        Preconditions.checkNotNull(getActionGraph(targetGraphAndTargets.getTargetGraph()));
    DefaultSourcePathResolver sourcePathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(result.getActionGraphBuilder()));

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
      Path generatedSrc = sourcePathResolver.getAbsolutePath(sourcePath);

      if (projectFilesystem.isDirectory(generatedSrc)) {
        projectFilesystem.copyFolder(generatedSrc, desiredPath);
      } else {
        projectFilesystem.copyFile(generatedSrc, desiredPath.resolve(generatedSrc.getFileName()));
      }
    }
  }

  /**
   * Find code generation targets by inspecting go_library targets in the target graph with "srcs"
   * pointing to other Buck targets rather than Go files. Those Buck targets in "srcs" are assumed
   * to be code generation targets. Their output is intended to be used as some package name, either
   * specified by package_name argument of go_library, or guessed from the base path of the the
   * go_library
   */
  private Map<BuildTargetSourcePath, Path> findCodeGenerationTargets(
      TargetGraphAndTargets targetGraphAndTargets) {
    Map<BuildTargetSourcePath, Path> generatedPackages = new HashMap<>();
    for (TargetNode<?> targetNode : targetGraphAndTargets.getTargetGraph().getNodes()) {
      Object constructorArg = targetNode.getConstructorArg();
      if (constructorArg instanceof GoLibraryDescriptionArg) {
        Optional<String> packageNameArg =
            ((GoLibraryDescriptionArg) constructorArg).getPackageName();
        Path pkgName =
            packageNameArg
                .map(Paths::get)
                .orElse(goBuckConfig.getDefaultPackageName(targetNode.getBuildTarget()));
        generatedPackages.putAll(
            ((HasSrcs) constructorArg)
                .getSrcs()
                .stream()
                .filter(srcPath -> srcPath instanceof BuildTargetSourcePath)
                .collect(Collectors.toMap(src -> (BuildTargetSourcePath) src, src -> pkgName)));
      }
    }
    return generatedPackages;
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

  private TargetGraphAndTargets createTargetGraph(
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> graphRoots,
      boolean needsFullRecursiveParse,
      ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, VersionException {

    boolean isWithTests = isWithTests();
    ImmutableSet<BuildTarget> explicitTestTargets = ImmutableSet.of();

    if (needsFullRecursiveParse) {
      return TargetGraphAndTargets.create(
          graphRoots, projectGraph, isWithTests, explicitTestTargets);
    }

    if (isWithTests) {
      explicitTestTargets = getExplicitTestTargets(graphRoots, projectGraph);
      projectGraph =
          parser.buildTargetGraph(
              cell, enableParserProfiling, executor, Sets.union(graphRoots, explicitTestTargets));
    }

    TargetGraphAndTargets targetGraphAndTargets =
        TargetGraphAndTargets.create(graphRoots, projectGraph, isWithTests, explicitTestTargets);
    if (buckConfig.getBuildVersions()) {
      targetGraphAndTargets =
          VersionedTargetGraphAndTargets.toVersionedTargetGraphAndTargets(
              targetGraphAndTargets,
              params.getVersionedTargetGraphCache(),
              buckEventBus,
              buckConfig,
              params.getTypeCoercerFactory(),
              explicitTestTargets);
    }
    return targetGraphAndTargets;
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
    return TargetGraphAndTargets.getExplicitTestTargets(nodes.iterator());
  }

  private ExitCode runBuild(ImmutableSet<BuildTarget> targets)
      throws IOException, InterruptedException {
    BuildCommand buildCommand =
        new BuildCommand(
            targets.stream().map(Object::toString).collect(ImmutableList.toImmutableList()));
    buildCommand.setKeepGoing(true);
    return buildCommand.run(params);
  }
}

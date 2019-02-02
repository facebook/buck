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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.cli.ProjectGeneratorParameters;
import com.facebook.buck.cli.ProjectTestsMode;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphAndTargets;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternParser;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.test.selectors.Nullable;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionException;
import com.facebook.buck.versions.VersionedTargetGraphAndTargets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class IjProjectCommandHelper {

  private final BuckEventBus buckEventBus;
  private final Console console;
  private final ListeningExecutorService executor;
  private final Parser parser;
  private final BuckConfig buckConfig;
  private final ActionGraphProvider actionGraphProvider;
  private final InstrumentedVersionedTargetGraphCache versionedTargetGraphCache;
  private final TypeCoercerFactory typeCoercerFactory;
  private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;
  private final Cell cell;
  private final IjProjectConfig projectConfig;
  private final boolean enableParserProfiling;
  private final boolean processAnnotations;
  private final boolean updateOnly;
  private final @Nullable String outputDir;
  private final BuckBuildRunner buckBuildRunner;
  private final Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser;

  private final ProjectGeneratorParameters projectGeneratorParameters;

  public IjProjectCommandHelper(
      BuckEventBus buckEventBus,
      ListeningExecutorService executor,
      BuckConfig buckConfig,
      ActionGraphProvider actionGraphProvider,
      InstrumentedVersionedTargetGraphCache versionedTargetGraphCache,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      Cell cell,
      IjProjectConfig projectConfig,
      boolean enableParserProfiling,
      boolean processAnnotations,
      boolean updateOnly,
      String outputDir,
      BuckBuildRunner buckBuildRunner,
      Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser,
      ProjectGeneratorParameters projectGeneratorParameters) {
    this.buckEventBus = buckEventBus;
    this.console = projectGeneratorParameters.getConsole();
    this.executor = executor;
    this.parser = projectGeneratorParameters.getParser();
    this.buckConfig = buckConfig;
    this.actionGraphProvider = actionGraphProvider;
    this.versionedTargetGraphCache = versionedTargetGraphCache;
    this.typeCoercerFactory = typeCoercerFactory;
    this.cell = cell;
    this.projectConfig = projectConfig;
    this.enableParserProfiling = enableParserProfiling;
    this.processAnnotations = processAnnotations;
    this.updateOnly = updateOnly;
    this.outputDir = outputDir;
    this.buckBuildRunner = buckBuildRunner;
    this.argsParser = argsParser;

    this.projectGeneratorParameters = projectGeneratorParameters;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
  }

  public ExitCode parseTargetsAndRunProjectGenerator(List<String> arguments)
      throws IOException, InterruptedException {
    if (updateOnly && projectConfig.getAggregationMode() != AggregationMode.NONE) {
      throw new CommandLineException(
          "`--regenerate` option is incompatible with IntelliJ"
              + " module aggregation. In order to use `--regenerate` set `--intellij-aggregation-mode=none`");
    }

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

    return runIntellijProjectGenerator(targetGraphAndTargets);
  }

  private ActionGraphAndBuilder getActionGraph(TargetGraph targetGraph) {
    TargetNodeToBuildRuleTransformer transformer = new ShallowTargetNodeToBuildRuleTransformer();
    return actionGraphProvider.getFreshActionGraph(transformer, targetGraph);
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
    Preconditions.checkState(!passedInTargets.isEmpty());
    return parser.buildTargetGraph(cell, enableParserProfiling, executor, passedInTargets);
  }

  /** Run intellij specific project generation actions. */
  private ExitCode runIntellijProjectGenerator(TargetGraphAndTargets targetGraphAndTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> requiredBuildTargets =
        writeProjectAndGetRequiredBuildTargets(targetGraphAndTargets);

    if (requiredBuildTargets.isEmpty()) {
      return ExitCode.SUCCESS;
    }

    if (projectConfig.isSkipBuildEnabled()) {
      ConsoleEvent.severe(
          "Please remember to buck build --deep the targets you intent to work with.");
      return ExitCode.SUCCESS;
    }

    return processAnnotations
        ? buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
            targetGraphAndTargets, requiredBuildTargets)
        : runBuild(requiredBuildTargets);
  }

  private ProjectFilesystem getProjectOutputFilesystem() throws IOException {
    if (outputDir != null) {
      Path outputPath = Paths.get(outputDir).toAbsolutePath();
      Files.createDirectories(outputPath);
      return new DefaultProjectFilesystemFactory().createProjectFilesystem(outputPath);
    } else {
      return cell.getFilesystem();
    }
  }

  private ImmutableSet<BuildTarget> writeProjectAndGetRequiredBuildTargets(
      TargetGraphAndTargets targetGraphAndTargets) throws IOException {
    ActionGraphAndBuilder result =
        Objects.requireNonNull(getActionGraph(targetGraphAndTargets.getTargetGraph()));

    ActionGraphBuilder graphBuilder = result.getActionGraphBuilder();

    JavacOptions javacOptions = buckConfig.getView(JavaBuckConfig.class).getDefaultJavacOptions();

    IjProject project =
        new IjProject(
            targetGraphAndTargets,
            getJavaPackageFinder(buckConfig),
            JavaFileParser.createJavaFileParser(javacOptions),
            graphBuilder,
            cell.getFilesystem(),
            projectConfig,
            getProjectOutputFilesystem());

    final ImmutableSet<BuildTarget> buildTargets;
    if (updateOnly) {
      buildTargets = project.update();
    } else {
      buildTargets = project.write();
    }
    return buildTargets;
  }

  private ExitCode buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
      TargetGraphAndTargets targetGraphAndTargets, ImmutableSet<BuildTarget> requiredBuildTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> annotatedTargets =
        getTargetsWithAnnotations(targetGraphAndTargets.getTargetGraph(), requiredBuildTargets);

    ImmutableSet<BuildTarget> unannotatedTargets =
        Sets.difference(requiredBuildTargets, annotatedTargets).immutableCopy();

    ExitCode exitCode = runBuild(unannotatedTargets);
    if (exitCode != ExitCode.SUCCESS) {
      addBuildFailureError();
    }

    if (annotatedTargets.isEmpty()) {
      return exitCode;
    }

    ExitCode annotationExitCode = buckBuildRunner.runBuild(annotatedTargets, true);
    if (exitCode == ExitCode.SUCCESS && annotationExitCode != ExitCode.SUCCESS) {
      addBuildFailureError();
    }

    return exitCode == ExitCode.SUCCESS ? annotationExitCode : exitCode;
  }

  private ExitCode runBuild(ImmutableSet<BuildTarget> targets)
      throws IOException, InterruptedException {
    return buckBuildRunner.runBuild(targets, false);
  }

  private ImmutableSet<BuildTarget> getTargetsWithAnnotations(
      TargetGraph targetGraph, ImmutableSet<BuildTarget> buildTargets) {
    return buildTargets
        .stream()
        .filter(
            input -> {
              TargetNode<?> targetNode = targetGraph.get(input);
              return targetNode != null && isTargetWithAnnotations(targetNode);
            })
        .collect(ImmutableSet.toImmutableSet());
  }

  private void addBuildFailureError() {
    console
        .getAnsi()
        .printHighlightedSuccessText(
            console.getStdErr(),
            "Because the build did not complete successfully some parts of the project may not\n"
                + "work correctly with IntelliJ. Please fix the errors and run this command again.\n");
  }

  private static boolean isTargetWithAnnotations(TargetNode<?> target) {
    if (target.getDescription() instanceof JavaLibraryDescription) {
      return false;
    }
    JavaLibraryDescriptionArg arg = ((JavaLibraryDescriptionArg) target.getConstructorArg());
    return !arg.getAnnotationProcessors().isEmpty();
  }

  public JavaPackageFinder getJavaPackageFinder(BuckConfig buckConfig) {
    return buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();
  }

  private ProjectTestsMode testsMode() {
    ProjectTestsMode parameterMode = ProjectTestsMode.WITH_TESTS;

    // TODO(shemitz) Just refactoring the existing incoherence ... really need to clean this up
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
              versionedTargetGraphCache,
              buckEventBus,
              buckConfig,
              typeCoercerFactory,
              unconfiguredBuildTargetFactory,
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
    ImmutableSet<BuildTarget> tests =
        TargetGraphAndTargets.getExplicitTestTargets(nodes.iterator());

    return filterTests(
        tests,
        cell.getCellPathResolver(),
        projectConfig.getIncludeTestPatterns(),
        projectConfig.getExcludeTestPatterns());
  }

  public static ImmutableSet<BuildTarget> filterTests(
      ImmutableSet<BuildTarget> testTargets,
      CellPathResolver cellPathResolver,
      ImmutableSet<String> includes,
      ImmutableSet<String> excludes) {
    BuildTargetPatternParser<BuildTargetPattern> parser =
        BuildTargetPatternParser.forVisibilityArgument();
    ImmutableSet<BuildTargetPattern> includePatterns =
        getPatterns(parser, cellPathResolver, includes);
    ImmutableSet<BuildTargetPattern> excludePatterns =
        getPatterns(parser, cellPathResolver, excludes);
    return RichStream.from(testTargets)
        .filter(
            test ->
                (includePatterns.isEmpty()
                        || includePatterns.stream().anyMatch(pattern -> pattern.matches(test)))
                    && (excludePatterns.isEmpty()
                        || excludePatterns.stream().noneMatch(pattern -> pattern.matches(test))))
        .toImmutableSet();
  }

  private static ImmutableSet<BuildTargetPattern> getPatterns(
      BuildTargetPatternParser<BuildTargetPattern> parser,
      CellPathResolver cellPathResolver,
      ImmutableSet<String> patterns) {
    return RichStream.from(patterns)
        .map(pattern -> parser.parse(cellPathResolver, pattern))
        .toImmutableSet();
  }
}

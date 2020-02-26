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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.cli.ProjectGeneratorParameters;
import com.facebook.buck.cli.ProjectTestsMode;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcherParser;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.jvm.java.JavacLanguageLevelOptions;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.test.selectors.Nullable;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.collect.MoreSets;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class IjProjectCommandHelper {

  private final BuckEventBus buckEventBus;
  private final Console console;
  private final Parser parser;
  private final BuckConfig buckConfig;
  private final InstrumentedVersionedTargetGraphCache versionedTargetGraphCache;
  private final TypeCoercerFactory typeCoercerFactory;
  private final UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory;
  private final Cell cell;
  private final IjProjectConfig projectConfig;
  private final Optional<TargetConfiguration> targetConfiguration;
  private final boolean processAnnotations;
  private final boolean updateOnly;
  private final @Nullable String outputDir;
  private final BuckBuildRunner buckBuildRunner;
  private final Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser;
  private final ProjectGeneratorParameters projectGeneratorParameters;
  private final ParsingContext parsingContext;
  private final Supplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutorSupplier;

  public IjProjectCommandHelper(
      BuckEventBus buckEventBus,
      ListeningExecutorService executor,
      CloseableMemoizedSupplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutor,
      InstrumentedVersionedTargetGraphCache versionedTargetGraphCache,
      TypeCoercerFactory typeCoercerFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory,
      Cell cell,
      Optional<TargetConfiguration> targetConfiguration,
      IjProjectConfig projectConfig,
      boolean enableParserProfiling,
      boolean processAnnotations,
      boolean updateOnly,
      String outputDir,
      BuckBuildRunner buckBuildRunner,
      Function<Iterable<String>, ImmutableList<TargetNodeSpec>> argsParser,
      ProjectGeneratorParameters projectGeneratorParameters,
      BuckConfig buckConfig) {
    this.buckEventBus = buckEventBus;
    this.depsAwareExecutorSupplier = depsAwareExecutor;
    this.targetConfiguration = targetConfiguration;
    this.console = projectGeneratorParameters.getConsole();
    this.parser = projectGeneratorParameters.getParser();
    this.buckConfig = buckConfig;
    this.versionedTargetGraphCache = versionedTargetGraphCache;
    this.typeCoercerFactory = typeCoercerFactory;
    this.cell = cell;
    this.projectConfig = projectConfig;
    this.processAnnotations = processAnnotations;
    this.updateOnly = updateOnly;
    this.outputDir = outputDir;
    this.buckBuildRunner = buckBuildRunner;
    this.argsParser = argsParser;
    this.projectGeneratorParameters = projectGeneratorParameters;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.parsingContext =
        ParsingContext.builder(cell, executor)
            .setProfilingEnabled(enableParserProfiling)
            .setSpeculativeParsing(SpeculativeParsing.ENABLED)
            .setApplyDefaultFlavorsMode(
                buckConfig.getView(ParserConfig.class).getDefaultFlavorsMode())
            .build();
  }

  public ExitCode parseTargetsAndRunProjectGenerator(List<String> arguments)
      throws IOException, InterruptedException {
    if (updateOnly && projectConfig.getAggregationMode() != AggregationMode.NONE) {
      throw new CommandLineException(
          "`--update` option is incompatible with IntelliJ"
              + " module aggregation. In order to use `--update` set `--intellij-aggregation-mode=none`");
    }

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
          createTargetGraph(depsAwareExecutorSupplier, targetGraphCreationResult);
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

    return runIntellijProjectGenerator(targetGraphCreationResult);
  }

  /** Run intellij specific project generation actions. */
  private ExitCode runIntellijProjectGenerator(TargetGraphCreationResult targetGraphCreationResult)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> requiredBuildTargets =
        writeProjectAndGetRequiredBuildTargets(targetGraphCreationResult);

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
            targetGraphCreationResult.getTargetGraph(), requiredBuildTargets)
        : runBuild(requiredBuildTargets);
  }

  private ProjectFilesystem getProjectOutputFilesystem() throws IOException {
    if (outputDir != null) {
      AbsPath outputPath = AbsPath.of(Paths.get(outputDir).toAbsolutePath());
      Files.createDirectories(outputPath.getPath());
      Cell rootCell = this.cell.getCell(CanonicalCellName.rootCell());
      return new DefaultProjectFilesystemFactory()
          .createProjectFilesystem(
              this.cell.getCanonicalName(),
              outputPath,
              BuckPaths.getBuckOutIncludeTargetConfigHashFromRootCellConfig(
                  rootCell.getBuckConfig().getConfig()));
    } else {
      return cell.getFilesystem();
    }
  }

  private ImmutableSet<BuildTarget> writeProjectAndGetRequiredBuildTargets(
      TargetGraphCreationResult targetGraphCreationResult) throws IOException {
    JavacLanguageLevelOptions languageLevelOptions =
        buckConfig.getView(JavaBuckConfig.class).getJavacLanguageLevelOptions();

    IjProject project =
        new IjProject(
            targetGraphCreationResult.getTargetGraph(),
            getJavaPackageFinder(buckConfig),
            JavaFileParser.createJavaFileParser(languageLevelOptions),
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
      TargetGraph targetGraph, ImmutableSet<BuildTarget> requiredBuildTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> annotatedTargets =
        getTargetsWithAnnotations(targetGraph, requiredBuildTargets);

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
    return buildTargets.stream()
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

  private TargetGraphCreationResult createTargetGraph(
      Supplier<DepsAwareExecutor<? super ComputeResult, ?>> depsAwareExecutorSupplier,
      TargetGraphCreationResult targetGraphCreationResult)
      throws IOException, InterruptedException, BuildFileParseException, VersionException {

    if (isWithTests()) {
      ImmutableSet<BuildTarget> explicitTestTargets =
          getExplicitTestTargets(targetGraphCreationResult);
      targetGraphCreationResult =
          parser.buildTargetGraph(
              parsingContext,
              MoreSets.union(targetGraphCreationResult.getBuildTargets(), explicitTestTargets));
    }

    if (buckConfig.getView(BuildBuckConfig.class).getBuildVersions()) {
      targetGraphCreationResult =
          versionedTargetGraphCache.toVersionedTargetGraph(
              depsAwareExecutorSupplier.get(),
              buckConfig,
              typeCoercerFactory,
              unconfiguredBuildTargetFactory,
              targetGraphCreationResult,
              targetConfiguration,
              buckEventBus,
              new Cells(cell));
    }
    return targetGraphCreationResult;
  }

  /**
   * @param targetGraphCreationResult A target graph creation result containing all nodes and their
   *     tests in the target graph.
   * @return A set of all test targets that test any of build targets of {@code
   *     targetGraphCreationResult} or their dependencies.
   */
  private ImmutableSet<BuildTarget> getExplicitTestTargets(
      TargetGraphCreationResult targetGraphCreationResult) {
    Iterable<TargetNode<?>> projectRoots =
        targetGraphCreationResult
            .getTargetGraph()
            .getAll(targetGraphCreationResult.getBuildTargets());
    Iterable<TargetNode<?>> nodes;
    if (isWithDependenciesTests()) {
      nodes = targetGraphCreationResult.getTargetGraph().getSubgraph(projectRoots).getNodes();
    } else {
      nodes = projectRoots;
    }
    ImmutableSet<BuildTarget> tests = TargetNodes.getTestTargetsForNodes(nodes.iterator());

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
    BuildTargetMatcherParser<BuildTargetMatcher> parser =
        BuildTargetMatcherParser.forVisibilityArgument();
    ImmutableSet<BuildTargetMatcher> includePatterns =
        getPatterns(parser, cellPathResolver, includes);
    ImmutableSet<BuildTargetMatcher> excludePatterns =
        getPatterns(parser, cellPathResolver, excludes);
    return RichStream.from(testTargets)
        .filter(
            test ->
                (includePatterns.isEmpty()
                        || includePatterns.stream()
                            .anyMatch(
                                pattern -> pattern.matches(test.getUnconfiguredBuildTarget())))
                    && (excludePatterns.isEmpty()
                        || excludePatterns.stream()
                            .noneMatch(
                                pattern -> pattern.matches(test.getUnconfiguredBuildTarget()))))
        .toImmutableSet();
  }

  private static ImmutableSet<BuildTargetMatcher> getPatterns(
      BuildTargetMatcherParser<BuildTargetMatcher> parser,
      CellPathResolver cellPathResolver,
      ImmutableSet<String> patterns) {
    return RichStream.from(patterns)
        .map(pattern -> parser.parse(pattern, cellPathResolver.getCellNameResolver()))
        .toImmutableSet();
  }
}

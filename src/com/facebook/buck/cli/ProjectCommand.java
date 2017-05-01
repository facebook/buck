/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.apple.project_generator.FocusedModuleTargetMatcher;
import com.facebook.buck.apple.project_generator.ProjectGenerator;
import com.facebook.buck.apple.project_generator.WorkspaceAndProjectGenerator;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.ide.intellij.IjProject;
import com.facebook.buck.ide.intellij.IjProjectBuckConfig;
import com.facebook.buck.ide.intellij.aggregation.AggregationMode;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.ide.intellij.projectview.ProjectView;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaFileParser;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.SpeculativeParsing;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

public class ProjectCommand extends BuildCommand {

  private static final Logger LOG = Logger.get(ProjectCommand.class);

  private static final String XCODE_PROCESS_NAME = "Xcode";

  public enum Ide {
    INTELLIJ,
    XCODE;

    public static Ide fromString(String string) {
      switch (Ascii.toLowerCase(string)) {
        case "intellij":
          return Ide.INTELLIJ;
        case "xcode":
          return Ide.XCODE;
        default:
          throw new HumanReadableException("Invalid ide value %s.", string);
      }
    }
  }

  private static final boolean DEFAULT_READ_ONLY_VALUE = false;

  @Option(
    name = "--combined-project",
    usage = "Generate an xcode project of a target and its dependencies."
  )
  private boolean combinedProject;

  @Option(
    name = "--build-with-buck",
    usage = "Use Buck to build the generated project instead of delegating the build to the IDE."
  )
  boolean buildWithBuck;

  @Option(name = "--process-annotations", usage = "Enable annotation processing")
  private boolean processAnnotations;

  @Option(
    name = "--without-tests",
    usage = "When generating a project slice, exclude tests that test the code in that slice"
  )
  private boolean withoutTests = false;

  @Option(
    name = "--with-tests",
    usage = "When generating a project slice, generate with all the tests"
  )
  private boolean withTests = false;

  @Option(
    name = "--without-dependencies-tests",
    usage =
        "When generating a project slice, includes tests that test code in main target, "
            + "but exclude tests that test dependencies"
  )
  private boolean withoutDependenciesTests = false;

  @Option(
    name = "--ide",
    usage =
        "The type of IDE for which to generate a project. You may specify it in the "
            + ".buckconfig file. Please refer to https://buckbuild.com/concept/buckconfig.html#project"
  )
  @Nullable
  private Ide ide = null;

  @Option(
    name = "--read-only",
    usage =
        "If true, generate project files read-only. Defaults to '"
            + DEFAULT_READ_ONLY_VALUE
            + "' if not specified in .buckconfig. (Only "
            + "applies to generated Xcode projects.)"
  )
  private boolean readOnly = DEFAULT_READ_ONLY_VALUE;

  @Option(
    name = "--dry-run",
    usage =
        "Instead of actually generating the project, only print out the targets that "
            + "would be included."
  )
  private boolean dryRun = false;

  @Option(
    name = "--intellij-aggregation-mode",
    handler = AggregationModeOptionHandler.class,
    usage =
        "Changes how modules are aggregated. Valid options are 'none' (no aggregation), "
            + "'shallow' (Minimum of 3 directory levels deep), 'auto' (based on project size), or an "
            + "integer to specify the minimum directory depth modules should be aggregated to (e.g."
            + "specifying 3 would aggrgate modules to a/b/c from lower levels where possible). "
            + "Defaults to 'auto' if not specified in .buckconfig."
  )
  @Nullable
  private AggregationMode intellijAggregationMode = null;

  @Option(
    name = "--run-ij-cleaner",
    usage =
        "After generating an IntelliJ project using --experimental-ij-generation, start a "
            + "cleaner which removes any .iml files which weren't generated as part of the project."
  )
  private boolean runIjCleaner = false;

  @Option(
    name = "--remove-unused-ij-libraries",
    usage =
        "After generating an IntelliJ project remove all IntelliJ libraries that are not "
            + "used in the project."
  )
  private boolean removeUnusedLibraries = false;

  @Option(
    name = "--exclude-artifacts",
    usage =
        "Don't include references to the artifacts created by compiling a target in"
            + "the module representing that target."
  )
  private boolean excludeArtifacts = false;

  @Option(
    name = "--skip-build",
    usage = "Don't try to build any of the targets for the generated project."
  )
  private boolean skipBuild = false;

  @Option(name = "--build", usage = "Also build all the targets in the project.")
  private boolean build = true;

  @Option(
    name = "--focus",
    usage =
        "Space separated list of build target full qualified names that should be part of "
            + "focused project. "
            + "For example, //Libs/CommonLibs:BaseLib //Libs/ImportantLib:ImportantLib"
  )
  @Nullable
  private String modulesToFocusOn = null;

  @Option(
    name = "--file-with-list-of-generated-files",
    usage =
        "If present, forces command to save the list of generated file names to a provided"
            + " file"
  )
  @Nullable
  private String generatedFilesListFilename = null;

  @Option(
    name = "--view",
    usage =
        "Experimental command to build a 'project view', which is a directory outside the "
            + "repo, containing symlinks in to the repo. This directory looks a lot like a standard "
            + "IntelliJ project with all resources under /res, but what's really important is that it "
            + "generates a single IntelliJ module, so that editing is much faster than when you use "
            + "`buck project`."
  )
  @Nullable
  private String projectView = null;

  public boolean getCombinedProject() {
    return combinedProject;
  }

  public boolean getDryRun() {
    return dryRun;
  }

  public boolean shouldProcessAnnotations() {
    return processAnnotations;
  }

  public JavaPackageFinder getJavaPackageFinder(BuckConfig buckConfig) {
    return buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();
  }

  private Optional<String> getPathToPreProcessScript(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "pre_process");
  }

  public boolean getReadOnly(BuckConfig buckConfig) {
    if (readOnly) {
      return readOnly;
    }
    return buckConfig.getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  /**
   * Returns true if Buck should prompt to kill a running IDE before changing its files, false
   * otherwise.
   */
  public boolean getIdePrompt(BuckConfig buckConfig) {
    return buckConfig.getBooleanValue("project", "ide_prompt", true);
  }

  private Optional<Ide> getIdeFromBuckConfig(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "ide").map(Ide::fromString);
  }

  private ProjectTestsMode testsMode(BuckConfig buckConfig) {
    ProjectTestsMode parameterMode = ProjectTestsMode.WITH_TESTS;

    Ide projectIde = getIdeFromBuckConfig(buckConfig).orElse(null);
    if (projectIde == Ide.XCODE) {
      parameterMode = buckConfig.xcodeProjectTestsMode();
    }

    if (withoutTests) {
      parameterMode = ProjectTestsMode.WITHOUT_TESTS;
    } else if (withoutDependenciesTests) {
      parameterMode = ProjectTestsMode.WITHOUT_DEPENDENCIES_TESTS;
    } else if (withTests) {
      parameterMode = ProjectTestsMode.WITH_TESTS;
    }

    return parameterMode;
  }

  public boolean isWithTests(BuckConfig buckConfig) {
    return testsMode(buckConfig) != ProjectTestsMode.WITHOUT_TESTS;
  }

  public boolean isWithDependenciesTests(BuckConfig buckConfig) {
    return testsMode(buckConfig) == ProjectTestsMode.WITH_TESTS;
  }

  private boolean getSkipBuildFromConfig(BuckConfig buckConfig) {
    return buckConfig.getBooleanValue("project", "skip_build", false);
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (projectView != null && getArguments().isEmpty()) {
      params
          .getConsole()
          .getStdErr()
          .println("\nParams are view_path target(s), but you didn't supply any targets");

      return 1;
    }

    int rc = runPreprocessScriptIfNeeded(params);
    if (rc != 0) {
      return rc;
    }

    try (CommandThreadManager pool =
        new CommandThreadManager("Project", getConcurrencyLimit(params.getBuckConfig()))) {

      ImmutableSet<BuildTarget> passedInTargetsSet;
      TargetGraph projectGraph;

      List<String> targets = getArguments();
      Ide projectIde = getIdeFromBuckConfig(params.getBuckConfig()).orElse(null);

      if (projectIde != Ide.XCODE && targets.isEmpty()) {
        targets = ImmutableList.of("//...");
      }

      try {
        ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
        passedInTargetsSet =
            ImmutableSet.copyOf(
                Iterables.concat(
                    params
                        .getParser()
                        .resolveTargetSpecs(
                            params.getBuckEventBus(),
                            params.getCell(),
                            getEnableParserProfiling(),
                            pool.getExecutor(),
                            parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), targets),
                            SpeculativeParsing.of(true),
                            parserConfig.getDefaultFlavorsMode())));
        projectGraph = getProjectGraphForIde(params, pool.getExecutor(), passedInTargetsSet);
      } catch (BuildTargetException | BuildFileParseException | HumanReadableException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      if (projectView != null) {
        return ProjectView.run(
            params.getConsole().getStdErr(),
            getDryRun(),
            projectView,
            projectGraph,
            passedInTargetsSet,
            params.getBuckEventBus(),
            params.getBuckConfig().getConfig());
      }

      projectIde =
          getIdeBasedOnPassedInTargetsAndProjectGraph(
              params.getBuckConfig(), passedInTargetsSet, Optional.of(projectGraph));
      if (projectIde == ProjectCommand.Ide.XCODE) {
        checkForAndKillXcodeIfRunning(params, getIdePrompt(params.getBuckConfig()));
      }

      final Ide finalIde = projectIde;
      ImmutableSet<BuildTarget> graphRoots;
      if (passedInTargetsSet.isEmpty()) {
        graphRoots =
            getRootsFromPredicate(
                projectGraph,
                node ->
                    finalIde == ProjectCommand.Ide.XCODE
                        && node.getDescription() instanceof XcodeWorkspaceConfigDescription);
      } else {
        graphRoots = passedInTargetsSet;
      }

      TargetGraphAndTargets targetGraphAndTargets;
      try {
        targetGraphAndTargets =
            createTargetGraph(
                params,
                projectGraph,
                graphRoots,
                isWithTests(params.getBuckConfig()),
                isWithDependenciesTests(params.getBuckConfig()),
                passedInTargetsSet.isEmpty(),
                pool.getExecutor());
      } catch (BuildFileParseException
          | TargetGraph.NoSuchNodeException
          | BuildTargetException
          | HumanReadableException e) {
        params
            .getBuckEventBus()
            .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
        return 1;
      }

      if (getDryRun()) {
        for (TargetNode<?, ?> targetNode : targetGraphAndTargets.getTargetGraph().getNodes()) {
          params.getConsole().getStdOut().println(targetNode.toString());
        }

        return 0;
      }

      params.getBuckEventBus().post(ProjectGenerationEvent.started());
      int result;
      try {
        switch (projectIde) {
          case INTELLIJ:
            result = runIntellijProjectGenerator(params, targetGraphAndTargets);
            break;
          case XCODE:
            result =
                runXcodeProjectGenerator(
                    params, pool.getExecutor(), targetGraphAndTargets, passedInTargetsSet);
            break;
          default:
            // unreachable
            throw new IllegalStateException("'ide' should always be of type 'INTELLIJ' or 'XCODE'");
        }

      } finally {
        params.getBuckEventBus().post(ProjectGenerationEvent.finished());
      }

      return result;
    }
  }

  private Ide getIdeBasedOnPassedInTargetsAndProjectGraph(
      BuckConfig buckConfig,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      Optional<TargetGraph> projectGraph) {
    if (ide != null) {
      return ide;
    }
    Ide projectIde = getIdeFromBuckConfig(buckConfig).orElse(null);
    if (projectIde == null && !passedInTargetsSet.isEmpty() && projectGraph.isPresent()) {
      Ide guessedIde = null;
      for (BuildTarget buildTarget : passedInTargetsSet) {
        Optional<TargetNode<?, ?>> node = projectGraph.get().getOptional(buildTarget);
        if (!node.isPresent()) {
          throw new HumanReadableException(
              "Project graph %s doesn't contain build target " + "%s",
              projectGraph.get(), buildTarget);
        }
        Description<?> description = node.get().getDescription();
        boolean canGenerateXcodeProject = canGenerateImplicitWorkspaceForDescription(description);
        canGenerateXcodeProject |= description instanceof XcodeWorkspaceConfigDescription;
        if (guessedIde == null && canGenerateXcodeProject) {
          guessedIde = Ide.XCODE;
        } else if (guessedIde == Ide.XCODE && !canGenerateXcodeProject
            || guessedIde == Ide.INTELLIJ && canGenerateXcodeProject) {
          throw new HumanReadableException(
              "Passed targets (%s) contain both Xcode and Idea "
                  + "projects.\nCan't choose Ide from this mixed set. "
                  + "Please pass only Xcode targets or only Idea targets.",
              passedInTargetsSet);
        } else {
          guessedIde = Ide.INTELLIJ;
        }
      }
      projectIde = guessedIde;
    }
    if (projectIde == null) {
      throw new HumanReadableException(
          "Please specify ide using --ide option or set ide in " + ".buckconfig");
    }
    return projectIde;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  /** Run intellij specific project generation actions. */
  int runIntellijProjectGenerator(
      CommandRunnerParams params, final TargetGraphAndTargets targetGraphAndTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> requiredBuildTargets =
        writeProjectAndGetRequiredBuildTargets(params, targetGraphAndTargets);

    if (requiredBuildTargets.isEmpty()) {
      return 0;
    }

    boolean skipBuilds = skipBuild || getSkipBuildFromConfig(params.getBuckConfig()) || !build;
    if (skipBuilds) {
      ConsoleEvent.severe(
          "Please remember to buck build --deep the targets you intent to work with.");
      return 0;
    }

    return shouldProcessAnnotations()
        ? buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
            params, targetGraphAndTargets, requiredBuildTargets)
        : runBuild(params, requiredBuildTargets);
  }

  private ImmutableSet<BuildTarget> writeProjectAndGetRequiredBuildTargets(
      CommandRunnerParams params, final TargetGraphAndTargets targetGraphAndTargets)
      throws IOException {
    ActionGraphAndResolver result =
        Preconditions.checkNotNull(
            params
                .getActionGraphCache()
                .getActionGraph(
                    params.getBuckEventBus(),
                    params.getBuckConfig().isActionGraphCheckingEnabled(),
                    params.getBuckConfig().isSkipActionGraphCache(),
                    targetGraphAndTargets.getTargetGraph(),
                    params.getBuckConfig().getKeySeed()));

    BuckConfig buckConfig = params.getBuckConfig();
    BuildRuleResolver ruleResolver = result.getResolver();

    JavacOptions javacOptions = buckConfig.getView(JavaBuckConfig.class).getDefaultJavacOptions();

    IjProjectConfig projectConfig =
        IjProjectBuckConfig.create(
            buckConfig,
            intellijAggregationMode,
            generatedFilesListFilename,
            runIjCleaner,
            removeUnusedLibraries,
            excludeArtifacts);

    IjProject project =
        new IjProject(
            targetGraphAndTargets,
            getJavaPackageFinder(buckConfig),
            JavaFileParser.createJavaFileParser(javacOptions),
            ruleResolver,
            params.getCell().getFilesystem(),
            projectConfig);

    return project.write();
  }

  private int buildRequiredTargetsWithoutUsingCacheForAnnotatedTargets(
      CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> requiredBuildTargets)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> annotatedTargets =
        getTargetsWithAnnotations(targetGraphAndTargets.getTargetGraph(), requiredBuildTargets);

    ImmutableSet<BuildTarget> unannotatedTargets =
        Sets.difference(requiredBuildTargets, annotatedTargets).immutableCopy();

    int exitCode = runBuild(params, unannotatedTargets);
    if (exitCode != 0) {
      addBuildFailureError(params);
    }

    if (annotatedTargets.isEmpty()) {
      return exitCode;
    }

    int annotationExitCode = runBuild(params, annotatedTargets, true);
    if (exitCode == 0 && annotationExitCode != 0) {
      addBuildFailureError(params);
    }

    return exitCode == 0 ? annotationExitCode : exitCode;
  }

  private int runBuild(CommandRunnerParams params, ImmutableSet<BuildTarget> targets)
      throws IOException, InterruptedException {
    return runBuild(params, targets, false);
  }

  private int runBuild(
      CommandRunnerParams params, ImmutableSet<BuildTarget> targets, boolean disableCaching)
      throws IOException, InterruptedException {
    BuildCommand buildCommand =
        new BuildCommand(
            targets.stream().map(Object::toString).collect(MoreCollectors.toImmutableList()));
    buildCommand.setKeepGoing(true);
    buildCommand.setArtifactCacheDisabled(disableCaching);
    return buildCommand.run(params);
  }

  private ImmutableSet<BuildTarget> getTargetsWithAnnotations(
      final TargetGraph targetGraph, ImmutableSet<BuildTarget> buildTargets) {
    return FluentIterable.from(buildTargets)
        .filter(
            input -> {
              TargetNode<?, ?> targetNode = targetGraph.get(input);
              return targetNode != null && isTargetWithAnnotations(targetNode);
            })
        .toSet();
  }

  private void addBuildFailureError(CommandRunnerParams params) {
    params
        .getConsole()
        .getAnsi()
        .printHighlightedSuccessText(
            params.getConsole().getStdErr(),
            "Because the build did not complete successfully some parts of the project may not\n"
                + "work correctly with IntelliJ. Please fix the errors and run this command again.\n");
  }

  private int runPreprocessScriptIfNeeded(CommandRunnerParams params)
      throws IOException, InterruptedException {
    Optional<String> pathToPreProcessScript = getPathToPreProcessScript(params.getBuckConfig());
    if (!pathToPreProcessScript.isPresent()) {
      return 0;
    }

    String pathToScript = pathToPreProcessScript.get();
    if (!Paths.get(pathToScript).isAbsolute()) {
      pathToScript =
          params
              .getCell()
              .getFilesystem()
              .getPathForRelativePath(pathToScript)
              .toAbsolutePath()
              .toString();
    }

    ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .addCommand(pathToScript)
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(params.getEnvironment())
                    .put("BUCK_PROJECT_TARGETS", Joiner.on(" ").join(getArguments()))
                    .build())
            .setDirectory(params.getCell().getFilesystem().getRootPath())
            .build();
    ForwardingProcessListener processListener =
        new ForwardingProcessListener(
            // Using rawStream to avoid shutting down SuperConsole. This is safe to do
            // because this process finishes before we start parsing process.
            Channels.newChannel(params.getConsole().getStdOut().getRawStream()),
            Channels.newChannel(params.getConsole().getStdErr().getRawStream()));
    ListeningProcessExecutor.LaunchedProcess process =
        processExecutor.launchProcess(processExecutorParams, processListener);
    try {
      return processExecutor.waitForProcess(process);
    } finally {
      processExecutor.destroyProcess(process, /* force */ false);
      processExecutor.waitForProcess(process);
    }
  }

  /** Run xcode specific project generation actions. */
  private int runXcodeProjectGenerator(
      final CommandRunnerParams params,
      ListeningExecutorService executor,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet)
      throws IOException, InterruptedException {
    int exitCode = 0;
    AppleConfig appleConfig = params.getBuckConfig().getView(AppleConfig.class);
    ImmutableSet<ProjectGenerator.Option> options =
        buildWorkspaceGeneratorOptions(
            getReadOnly(params.getBuckConfig()),
            isWithTests(params.getBuckConfig()),
            isWithDependenciesTests(params.getBuckConfig()),
            getCombinedProject(),
            appleConfig.shouldUseHeaderMapsInXcodeProject(),
            appleConfig.shouldMergeHeaderMapsInXcodeProject(),
            appleConfig.shouldGenerateHeaderSymlinkTreesOnly());

    ImmutableSet<BuildTarget> requiredBuildTargets =
        generateWorkspacesForTargets(
            params,
            targetGraphAndTargets,
            passedInTargetsSet,
            options,
            getFocusModules(params, executor),
            new HashMap<>(),
            getCombinedProject());
    if (!requiredBuildTargets.isEmpty()) {
      ImmutableMultimap<Path, String> cellPathToCellName =
          params.getCell().getCellPathResolver().getCellPaths().asMultimap().inverse();
      BuildCommand buildCommand =
          new BuildCommand(
              RichStream.from(requiredBuildTargets)
                  .map(
                      target -> {
                        if (!target.getCellPath().equals(params.getCell().getRoot())) {
                          Optional<String> cellName =
                              cellPathToCellName.get(target.getCellPath()).stream().findAny();
                          if (cellName.isPresent()) {
                            return target.withUnflavoredBuildTarget(
                                UnflavoredBuildTarget.of(
                                    target.getCellPath(),
                                    cellName,
                                    target.getBaseName(),
                                    target.getShortName()));
                          } else {
                            throw new IllegalStateException(
                                "Failed to find cell name for cell path while constructing parameters to "
                                    + "build dependencies for project generation. "
                                    + "Build target: "
                                    + target
                                    + " cell path: "
                                    + target.getCellPath());
                          }
                        } else {
                          return target;
                        }
                      })
                  .map(Object::toString)
                  .toImmutableList());
      exitCode = buildCommand.runWithoutHelp(params);
    }
    return exitCode;
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> generateWorkspacesForTargets(
      final CommandRunnerParams params,
      final TargetGraphAndTargets targetGraphAndTargets,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      ImmutableSet<ProjectGenerator.Option> options,
      FocusedModuleTargetMatcher focusModules,
      Map<Path, ProjectGenerator> projectGenerators,
      boolean combinedProject)
      throws IOException, InterruptedException {
    ImmutableSet<BuildTarget> targets;
    if (passedInTargetsSet.isEmpty()) {
      targets =
          targetGraphAndTargets
              .getProjectRoots()
              .stream()
              .map(TargetNode::getBuildTarget)
              .collect(MoreCollectors.toImmutableSet());
    } else {
      targets = passedInTargetsSet;
    }

    LazyActionGraph lazyActionGraph =
        new LazyActionGraph(targetGraphAndTargets.getTargetGraph(), params.getBuckEventBus());

    LOG.debug("Generating workspace for config targets %s", targets);
    ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder = ImmutableSet.builder();
    for (final BuildTarget inputTarget : targets) {
      TargetNode<?, ?> inputNode = targetGraphAndTargets.getTargetGraph().get(inputTarget);
      XcodeWorkspaceConfigDescription.Arg workspaceArgs;
      if (inputNode.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescription.Arg, ?> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(inputNode);
        workspaceArgs = castedWorkspaceNode.getConstructorArg();
      } else if (canGenerateImplicitWorkspaceForDescription(inputNode.getDescription())) {
        workspaceArgs = createImplicitWorkspaceArgs(inputNode);
      } else {
        throw new HumanReadableException(
            "%s must be a xcode_workspace_config, apple_binary, apple_bundle, or apple_library",
            inputNode);
      }

      BuckConfig buckConfig = params.getBuckConfig();
      AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
      HalideBuckConfig halideBuckConfig = new HalideBuckConfig(buckConfig);
      CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
      SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(buckConfig);

      CxxPlatform defaultCxxPlatform =
          params.getCell().getKnownBuildRuleTypes().getDefaultCxxPlatforms();
      WorkspaceAndProjectGenerator generator =
          new WorkspaceAndProjectGenerator(
              params.getCell(),
              targetGraphAndTargets.getTargetGraph(),
              workspaceArgs,
              inputTarget,
              options,
              combinedProject,
              focusModules,
              !appleConfig.getXcodeDisableParallelizeBuild(),
              defaultCxxPlatform,
              params.getBuckConfig().getView(ParserConfig.class).getBuildFileName(),
              lazyActionGraph::getBuildRuleResolverWhileRequiringSubgraph,
              params.getBuckEventBus(),
              halideBuckConfig,
              cxxBuckConfig,
              swiftBuckConfig);
      ListeningExecutorService executorService = params.getExecutors().get(ExecutorPool.PROJECT);
      Preconditions.checkNotNull(
          executorService, "CommandRunnerParams does not have executor for PROJECT pool");
      generator.generateWorkspaceAndDependentProjects(projectGenerators, executorService);
      ImmutableSet<BuildTarget> requiredBuildTargetsForWorkspace =
          generator.getRequiredBuildTargets();
      LOG.debug(
          "Required build targets for workspace %s: %s",
          inputTarget, requiredBuildTargetsForWorkspace);
      requiredBuildTargetsBuilder.addAll(requiredBuildTargetsForWorkspace);
    }

    return requiredBuildTargetsBuilder.build();
  }

  private FocusedModuleTargetMatcher getFocusModules(
      CommandRunnerParams params, ListeningExecutorService executor)
      throws IOException, InterruptedException {
    if (modulesToFocusOn == null) {
      return FocusedModuleTargetMatcher.noFocus();
    }

    Iterable<String> patterns = Splitter.onPattern("\\s+").split(modulesToFocusOn);
    // Parse patterns with the following syntax:
    // https://buckbuild.com/concept/build_target_pattern.html
    ImmutableList<TargetNodeSpec> specs =
        parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), patterns);

    // Resolve the list of targets matching the patterns.
    ImmutableSet<BuildTarget> passedInTargetsSet;
    ParserConfig parserConfig = params.getBuckConfig().getView(ParserConfig.class);
    try {
      passedInTargetsSet =
          params
              .getParser()
              .resolveTargetSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  executor,
                  specs,
                  SpeculativeParsing.of(false),
                  parserConfig.getDefaultFlavorsMode())
              .stream()
              .flatMap(Collection::stream)
              .map(target -> target.withoutCell())
              .collect(MoreCollectors.toImmutableSet());
    } catch (BuildTargetException | BuildFileParseException | HumanReadableException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return FocusedModuleTargetMatcher.noFocus();
    }
    LOG.debug("Selected targets: %s", passedInTargetsSet.toString());

    ImmutableSet<UnflavoredBuildTarget> passedInUnflavoredTargetsSet =
        RichStream.from(passedInTargetsSet)
            .map(BuildTarget::getUnflavoredBuildTarget)
            .toImmutableSet();
    LOG.debug("Selected unflavored targets: %s", passedInUnflavoredTargetsSet.toString());
    return FocusedModuleTargetMatcher.focusedOn(passedInUnflavoredTargetsSet);
  }

  public static ImmutableSet<ProjectGenerator.Option> buildWorkspaceGeneratorOptions(
      boolean isReadonly,
      boolean isWithTests,
      boolean isWithDependenciesTests,
      boolean isProjectsCombined,
      boolean shouldUseHeaderMaps,
      boolean shouldMergeHeaderMaps,
      boolean shouldGenerateHeaderSymlinkTreesOnly) {
    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    if (isReadonly) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES);
    }
    if (isWithTests) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_TESTS);
    }
    if (isWithDependenciesTests) {
      optionsBuilder.add(ProjectGenerator.Option.INCLUDE_DEPENDENCIES_TESTS);
    }
    if (isProjectsCombined) {
      optionsBuilder.addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS);
    } else {
      optionsBuilder.addAll(ProjectGenerator.SEPARATED_PROJECT_OPTIONS);
    }
    if (!shouldUseHeaderMaps) {
      optionsBuilder.add(ProjectGenerator.Option.DISABLE_HEADER_MAPS);
    }
    if (shouldMergeHeaderMaps) {
      optionsBuilder.add(ProjectGenerator.Option.MERGE_HEADER_MAPS);
    }
    if (shouldGenerateHeaderSymlinkTreesOnly) {
      optionsBuilder.add(ProjectGenerator.Option.GENERATE_HEADERS_SYMLINK_TREES_ONLY);
    }
    return optionsBuilder.build();
  }

  @SuppressWarnings(value = "unchecked")
  private static TargetNode<XcodeWorkspaceConfigDescription.Arg, ?> castToXcodeWorkspaceTargetNode(
      TargetNode<?, ?> targetNode) {
    Preconditions.checkArgument(
        targetNode.getDescription() instanceof XcodeWorkspaceConfigDescription);
    return (TargetNode<XcodeWorkspaceConfigDescription.Arg, ?>) targetNode;
  }

  private void checkForAndKillXcodeIfRunning(CommandRunnerParams params, boolean enablePrompt)
      throws InterruptedException, IOException {
    Optional<ProcessManager> processManager = params.getProcessManager();
    if (!processManager.isPresent()) {
      LOG.warn("Could not check if Xcode is running (no process manager)");
      return;
    }

    if (!processManager.get().isProcessRunning(XCODE_PROCESS_NAME)) {
      LOG.debug("Xcode is not running.");
      return;
    }

    boolean canPromptResult = canPrompt(params.getEnvironment());
    if (enablePrompt && canPromptResult) {
      if (prompt(
          params,
          "Xcode is currently running. Buck will modify files Xcode currently has "
              + "open, which can cause it to become unstable.\n\n"
              + "Kill Xcode and continue?")) {
        processManager.get().killProcess(XCODE_PROCESS_NAME);
      } else {
        params
            .getConsole()
            .getStdOut()
            .println(
                params
                    .getConsole()
                    .getAnsi()
                    .asWarningText(
                        "Xcode is running. Generated projects might be lost or corrupted if Xcode "
                            + "currently has them open."));
      }
      params
          .getConsole()
          .getStdOut()
          .format(
              "To disable this prompt in the future, add the following to %s: \n\n"
                  + "[project]\n"
                  + "  ide_prompt = false\n\n",
              params
                  .getCell()
                  .getFilesystem()
                  .getRootPath()
                  .resolve(BuckConfig.BUCK_CONFIG_OVERRIDE_FILE_NAME)
                  .toAbsolutePath());
    } else {
      LOG.debug(
          "Xcode is running, but cannot prompt to kill it (enabled %s, can prompt %s)",
          enablePrompt, canPromptResult);
    }
  }

  private boolean canPrompt(ImmutableMap<String, String> environment) {
    String nailgunStdinTty = environment.get("NAILGUN_TTY_0");
    if (nailgunStdinTty != null) {
      return nailgunStdinTty.equals("1");
    } else {
      return System.console() != null;
    }
  }

  private boolean prompt(CommandRunnerParams params, String prompt) throws IOException {
    Preconditions.checkState(canPrompt(params.getEnvironment()));

    LOG.debug("Displaying prompt %s..", prompt);
    params
        .getConsole()
        .getStdOut()
        .print(params.getConsole().getAnsi().asWarningText(prompt + " [Y/n] "));

    Optional<String> result;
    try (InputStreamReader stdinReader = new InputStreamReader(System.in, Charsets.UTF_8);
        BufferedReader bufferedStdinReader = new BufferedReader(stdinReader)) {
      result = Optional.ofNullable(bufferedStdinReader.readLine());
    }
    LOG.debug("Result of prompt: [%s]", result);
    return result.isPresent()
        && (result.get().isEmpty() || result.get().toLowerCase(Locale.US).startsWith("y"));
  }

  @VisibleForTesting
  static ImmutableSet<BuildTarget> getRootsFromPredicate(
      TargetGraph projectGraph, Predicate<TargetNode<?, ?>> rootsPredicate) {
    return FluentIterable.from(projectGraph.getNodes())
        .filter(rootsPredicate)
        .transform(TargetNode::getBuildTarget)
        .toSet();
  }

  private TargetGraph getProjectGraphForIde(
      CommandRunnerParams params,
      ListeningExecutorService executor,
      ImmutableSet<BuildTarget> passedInTargets)
      throws InterruptedException, BuildFileParseException, BuildTargetException, IOException {

    if (passedInTargets.isEmpty()) {
      return params
          .getParser()
          .buildTargetGraphForTargetNodeSpecs(
              params.getBuckEventBus(),
              params.getCell(),
              getEnableParserProfiling(),
              executor,
              ImmutableList.of(
                  TargetNodePredicateSpec.of(
                      x -> true,
                      BuildFileSpec.fromRecursivePath(Paths.get(""), params.getCell().getRoot()))),
              /* ignoreBuckAutodepsFiles */ false)
          .getTargetGraph();
    }
    Preconditions.checkState(!passedInTargets.isEmpty());
    return params
        .getParser()
        .buildTargetGraph(
            params.getBuckEventBus(),
            params.getCell(),
            getEnableParserProfiling(),
            executor,
            passedInTargets);
  }

  private TargetGraphAndTargets createTargetGraph(
      CommandRunnerParams params,
      TargetGraph projectGraph,
      ImmutableSet<BuildTarget> graphRoots,
      boolean isWithTests,
      boolean isWithDependenciesTests,
      boolean needsFullRecursiveParse,
      ListeningExecutorService executor)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {

    ImmutableSet<BuildTarget> explicitTestTargets = ImmutableSet.of();
    ImmutableSet<BuildTarget> graphRootsOrSourceTargets =
        replaceWorkspacesWithSourceTargetsIfPossible(graphRoots, projectGraph);

    if (isWithTests) {
      FocusedModuleTargetMatcher focusedModules = getFocusModules(params, executor);

      explicitTestTargets =
          getExplicitTestTargets(
              graphRootsOrSourceTargets, projectGraph, isWithDependenciesTests, focusedModules);
      if (!needsFullRecursiveParse) {
        projectGraph =
            params
                .getParser()
                .buildTargetGraph(
                    params.getBuckEventBus(),
                    params.getCell(),
                    getEnableParserProfiling(),
                    executor,
                    Sets.union(graphRoots, explicitTestTargets));
      } else {
        projectGraph =
            params
                .getParser()
                .buildTargetGraph(
                    params.getBuckEventBus(),
                    params.getCell(),
                    getEnableParserProfiling(),
                    executor,
                    Sets.union(
                        projectGraph
                            .getNodes()
                            .stream()
                            .map(TargetNode::getBuildTarget)
                            .collect(MoreCollectors.toImmutableSet()),
                        explicitTestTargets));
      }
    }

    return TargetGraphAndTargets.create(graphRoots, projectGraph, isWithTests, explicitTestTargets);
  }

  public static ImmutableSet<BuildTarget> replaceWorkspacesWithSourceTargetsIfPossible(
      ImmutableSet<BuildTarget> buildTargets, TargetGraph projectGraph) {
    Iterable<TargetNode<?, ?>> targetNodes = projectGraph.getAll(buildTargets);
    ImmutableSet.Builder<BuildTarget> resultBuilder = ImmutableSet.builder();
    for (TargetNode<?, ?> node : targetNodes) {
      if (node.getDescription() instanceof XcodeWorkspaceConfigDescription) {
        TargetNode<XcodeWorkspaceConfigDescription.Arg, ?> castedWorkspaceNode =
            castToXcodeWorkspaceTargetNode(node);
        Optional<BuildTarget> srcTarget = castedWorkspaceNode.getConstructorArg().srcTarget;
        if (srcTarget.isPresent()) {
          resultBuilder.add(srcTarget.get());
        } else {
          resultBuilder.add(node.getBuildTarget());
        }
      } else {
        resultBuilder.add(node.getBuildTarget());
      }
    }
    return resultBuilder.build();
  }

  private static boolean canGenerateImplicitWorkspaceForDescription(Description<?> description) {
    // We weren't given a workspace target, but we may have been given something that could
    // still turn into a workspace (for example, a library or an actual app rule). If that's the
    // case we still want to generate a workspace.
    return description instanceof AppleBinaryDescription
        || description instanceof AppleBundleDescription
        || description instanceof AppleLibraryDescription;
  }

  /**
   * @param sourceTargetNode - The TargetNode which will act as our fake workspaces `src_target`
   * @return Workspace Args that describe a generic Xcode workspace containing `src_target` and its
   *     tests
   */
  private static XcodeWorkspaceConfigDescription.Arg createImplicitWorkspaceArgs(
      TargetNode<?, ?> sourceTargetNode) {
    XcodeWorkspaceConfigDescription.Arg workspaceArgs = new XcodeWorkspaceConfigDescription.Arg();
    workspaceArgs.srcTarget = Optional.of(sourceTargetNode.getBuildTarget());
    workspaceArgs.actionConfigNames = ImmutableMap.of();
    workspaceArgs.extraTests = ImmutableSortedSet.of();
    workspaceArgs.extraTargets = ImmutableSortedSet.of();
    workspaceArgs.workspaceName = Optional.empty();
    workspaceArgs.extraSchemes = ImmutableSortedMap.of();
    workspaceArgs.isRemoteRunnable = Optional.empty();
    workspaceArgs.explicitRunnablePath = Optional.empty();
    workspaceArgs.launchStyle = Optional.empty();
    return workspaceArgs;
  }

  private static boolean isTargetWithAnnotations(TargetNode<?, ?> target) {
    if (target.getDescription() instanceof JavaLibraryDescription) {
      return false;
    }
    JavaLibraryDescription.Arg arg = ((JavaLibraryDescription.Arg) target.getConstructorArg());
    return !arg.annotationProcessors.isEmpty();
  }

  @Override
  public String getShortDescription() {
    return "generates project configuration files for an IDE";
  }

  /**
   * @param buildTargets The set of targets for which we would like to find tests
   * @param projectGraph A TargetGraph containing all nodes and their tests.
   * @param shouldIncludeDependenciesTests Should or not include tests that test dependencies
   * @return A set of all test targets that test any of {@code buildTargets} or their dependencies.
   */
  @VisibleForTesting
  static ImmutableSet<BuildTarget> getExplicitTestTargets(
      ImmutableSet<BuildTarget> buildTargets,
      TargetGraph projectGraph,
      boolean shouldIncludeDependenciesTests,
      FocusedModuleTargetMatcher focusedModules) {
    Iterable<TargetNode<?, ?>> projectRoots = projectGraph.getAll(buildTargets);
    Iterable<TargetNode<?, ?>> nodes;
    if (shouldIncludeDependenciesTests) {
      nodes = projectGraph.getSubgraph(projectRoots).getNodes();
    } else {
      nodes = projectRoots;
    }

    return TargetGraphAndTargets.getExplicitTestTargets(
        RichStream.from(nodes)
            .filter(node -> focusedModules.isFocusedOn(node.getBuildTarget()))
            .iterator());
  }

  public static class AggregationModeOptionHandler extends OptionHandler<AggregationMode> {

    public AggregationModeOptionHandler(
        CmdLineParser parser, OptionDef option, Setter<? super AggregationMode> setter) {
      super(parser, option, setter);
    }

    @Override
    public int parseArguments(Parameters params) throws CmdLineException {
      String param = params.getParameter(0);
      setter.addValue(AggregationMode.fromString(param));
      return 1;
    }

    @Override
    @Nullable
    public String getDefaultMetaVariable() {
      return null;
    }
  }

  /**
   * An action graph where subtrees are populated as needed.
   *
   * <p>This is useful when only select sub-graphs of the action graph needs to be generated, but
   * the subgraph is not known at this point in time. The synchronization and bottom-up traversal is
   * necessary as this will be accessed from multiple threads during project generation, and
   * BuildRuleResolver is not 100% thread safe when it comes to mutations.
   */
  @ThreadSafe
  private static class LazyActionGraph {
    private final TargetGraph targetGraph;
    private final BuildRuleResolver resolver;

    public LazyActionGraph(TargetGraph targetGraph, BuckEventBus buckEventBus) {
      this.targetGraph = targetGraph;
      this.resolver =
          new BuildRuleResolver(
              targetGraph, new DefaultTargetNodeToBuildRuleTransformer(), buckEventBus);
    }

    public BuildRuleResolver getBuildRuleResolverWhileRequiringSubgraph(TargetNode<?, ?> root) {
      TargetGraph subgraph = targetGraph.getSubgraph(ImmutableList.of(root));

      try {
        synchronized (this) {
          new AbstractBottomUpTraversal<TargetNode<?, ?>, NoSuchBuildTargetException>(subgraph) {
            @Override
            public void visit(TargetNode<?, ?> node) throws NoSuchBuildTargetException {
              resolver.requireRule(node.getBuildTarget());
            }
          }.traverse();
        }
      } catch (NoSuchBuildTargetException e) {
        throw new HumanReadableException(e);
      }
      return resolver;
    }
  }
}

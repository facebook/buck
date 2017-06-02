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

import com.facebook.buck.apple.project_generator.XCodeProjectCommandHelper;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.ide.intellij.IjProjectBuckConfig;
import com.facebook.buck.ide.intellij.IjProjectCommandHelper;
import com.facebook.buck.ide.intellij.aggregation.AggregationMode;
import com.facebook.buck.ide.intellij.model.IjProjectConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Paths;
import java.util.Optional;
import javax.annotation.Nullable;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

public class ProjectCommand extends BuildCommand {

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

  private Optional<String> getPathToPreProcessScript(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "pre_process");
  }

  private Optional<Ide> getIdeFromBuckConfig(BuckConfig buckConfig) {
    return buckConfig.getValue("project", "ide").map(Ide::fromString);
  }

  private boolean getReadOnly(BuckConfig buckConfig) {
    if (readOnly) {
      return readOnly;
    }
    return buckConfig.getBooleanValue("project", "read_only", DEFAULT_READ_ONLY_VALUE);
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    int rc = runPreprocessScriptIfNeeded(params);
    if (rc != 0) {
      return rc;
    }

    try (CommandThreadManager pool =
        new CommandThreadManager("Project", getConcurrencyLimit(params.getBuckConfig()))) {

      final Ide projectIde =
          (ide == null) ? getIdeFromBuckConfig(params.getBuckConfig()).orElse(null) : ide;

      if (projectIde == null) {
        params
            .getConsole()
            .getStdErr()
            .println("\nCannot build a project: project IDE is not specified.");
        return 1;
      }

      ListeningExecutorService executor = pool.getExecutor();

      params.getBuckEventBus().post(ProjectGenerationEvent.started());
      int result;
      try {
        switch (projectIde) {
          case INTELLIJ:
            IjProjectConfig projectConfig =
                IjProjectBuckConfig.create(
                    params.getBuckConfig(),
                    intellijAggregationMode,
                    generatedFilesListFilename,
                    runIjCleaner,
                    removeUnusedLibraries,
                    excludeArtifacts,
                    skipBuild || !build);

            IjProjectCommandHelper projectCommandHelper =
                new IjProjectCommandHelper(
                    params.getBuckEventBus(),
                    params.getConsole(),
                    executor,
                    params.getParser(),
                    params.getBuckConfig(),
                    params.getActionGraphCache(),
                    params.getCell(),
                    projectConfig,
                    processAnnotations,
                    getEnableParserProfiling(),
                    projectView,
                    dryRun,
                    withTests,
                    withoutTests,
                    withoutDependenciesTests,
                    (buildTargets, disableCaching) ->
                        runBuild(params, buildTargets, disableCaching),
                    arguments ->
                        parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), arguments));
            result = projectCommandHelper.parseTargetsAndRunProjectGenerator(getArguments());
            break;
          case XCODE:
            XCodeProjectCommandHelper xcodeProjectCommandHelper =
                new XCodeProjectCommandHelper(
                    params.getBuckEventBus(),
                    params.getParser(),
                    params.getBuckConfig(),
                    params.getCell(),
                    params.getConsole(),
                    params.getProcessManager(),
                    params.getEnvironment(),
                    params.getExecutors().get(ExecutorPool.PROJECT),
                    getArguments(),
                    getEnableParserProfiling(),
                    withTests,
                    withoutTests,
                    withoutDependenciesTests,
                    modulesToFocusOn,
                    combinedProject,
                    dryRun,
                    getReadOnly(params.getBuckConfig()),
                    arguments -> parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), arguments),
                    arguments -> {
                      try {
                        return runBuild(params, arguments);
                      } catch (IOException | InterruptedException e) {
                        throw new RuntimeException("Cannot run a build", e);
                      }
                    });
            result = xcodeProjectCommandHelper.parseTargetsAndRunXCodeGenerator(executor);
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

  @Override
  public boolean isReadOnly() {
    return false;
  }

  private int runBuild(CommandRunnerParams params, ImmutableList<String> arguments)
      throws IOException, InterruptedException {
    BuildCommand buildCommand = new BuildCommand(arguments);
    return buildCommand.run(params);
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

  @Override
  public String getShortDescription() {
    return "generates project configuration files for an IDE";
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
}

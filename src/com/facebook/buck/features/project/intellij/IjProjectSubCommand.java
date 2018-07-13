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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.artifact_cache.NoopArtifactCache.NoopArtifactCacheFactory;
import com.facebook.buck.cli.BuildCommand;
import com.facebook.buck.cli.CommandRunnerParams;
import com.facebook.buck.cli.CommandThreadManager;
import com.facebook.buck.cli.ProjectSubCommand;
import com.facebook.buck.cli.parameter_extractors.ProjectGeneratorParameters;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.features.project.intellij.aggregation.AggregationMode;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;
import org.pf4j.Extension;

@Extension
public class IjProjectSubCommand extends ProjectSubCommand {

  @Option(name = "--process-annotations", usage = "Enable annotation processing")
  private boolean processAnnotations;

  @Option(
      name = "--intellij-aggregation-mode",
      handler = AggregationModeOptionHandler.class,
      usage =
          "Changes how modules are aggregated. Valid options are 'none' (no aggregation), "
              + "'shallow' (Minimum of 3 directory levels deep), 'auto' (based on project size), or an "
              + "integer to specify the minimum directory depth modules should be aggregated to (e.g."
              + "specifying 3 would aggrgate modules to a/b/c from lower levels where possible). "
              + "Defaults to 'auto' if not specified in .buckconfig.")
  @Nullable
  private AggregationMode intellijAggregationMode = null;

  @Option(
      name = "--run-ij-cleaner",
      usage =
          "After generating an IntelliJ project using --experimental-ij-generation, start a "
              + "cleaner which removes any .iml files which weren't generated as part of the project.")
  private boolean runIjCleaner = false;

  @Option(
      name = "--remove-unused-ij-libraries",
      usage =
          "After generating an IntelliJ project remove all IntelliJ libraries that are not "
              + "used in the project.")
  private boolean removeUnusedLibraries = false;

  @Option(
      name = "--exclude-artifacts",
      usage =
          "Don't include references to the artifacts created by compiling a target in "
              + "the module representing that target.")
  private boolean excludeArtifacts = false;

  @Option(
      name = "--skip-build",
      usage = "Don't try to build any of the targets for the generated project.")
  private boolean skipBuild = false;

  @Option(name = "--build", usage = "Also build all the targets in the project.")
  private boolean build = true;

  @Option(
      name = "--intellij-project-root",
      usage =
          "Generate an Intellij project at specified folder.  Buck targets under this folder "
              + "are considered modules, and targets outside this folder are considered libraries.")
  @Nonnull
  private String projectRoot = "";

  @Option(
      name = "--intellij-include-transitive-dependencies",
      usage = "Include transitive dependencies as RUNTIME library for Intellij project.")
  @Nonnull
  private boolean includeTransitiveDependencies = false;

  @Option(
      name = "--intellij-module-group-name",
      usage = "Specify Intellij module group name when grouping modules into a module group.")
  private String moduleGroupName = null;

  @Option(
      name = "--file-with-list-of-generated-files",
      usage =
          "If present, forces command to save the list of generated file names to a provided"
              + " file")
  @Nullable
  private String generatedFilesListFilename = null;

  @Option(
      name = "--update",
      usage =
          "Instead of generating a whole project, only regenerate the module files for the "
              + "given targets, possibly updating the top-level modules list.")
  private boolean updateOnly = false;

  @Override
  public String getOptionValue() {
    return "intellij";
  }

  @Override
  public String getShortDescription() {
    return "project generation for IntelliJ";
  }

  @Override
  public ExitCode run(
      CommandRunnerParams params,
      CommandThreadManager threadManager,
      ProjectGeneratorParameters projectGeneratorParameters,
      List<String> projectCommandArguments)
      throws IOException, InterruptedException {
    ListeningExecutorService executor = threadManager.getListeningExecutorService();

    IjProjectConfig projectConfig =
        IjProjectBuckConfig.create(
            params.getBuckConfig(),
            intellijAggregationMode,
            generatedFilesListFilename,
            projectRoot,
            moduleGroupName,
            runIjCleaner,
            removeUnusedLibraries,
            excludeArtifacts,
            includeTransitiveDependencies,
            skipBuild || !build);

    IjProjectCommandHelper projectCommandHelper =
        new IjProjectCommandHelper(
            params.getBuckEventBus(),
            executor,
            params.getBuckConfig(),
            params.getActionGraphCache(),
            params.getVersionedTargetGraphCache(),
            params.getTypeCoercerFactory(),
            params.getCell(),
            params.getRuleKeyConfiguration(),
            projectConfig,
            projectGeneratorParameters.getEnableParserProfiling(),
            processAnnotations,
            updateOnly,
            (buildTargets, disableCaching) -> runBuild(params, buildTargets, disableCaching),
            projectGeneratorParameters.getArgsParser(),
            projectGeneratorParameters);
    return projectCommandHelper.parseTargetsAndRunProjectGenerator(projectCommandArguments);
  }

  private ExitCode runBuild(
      CommandRunnerParams params, ImmutableSet<BuildTarget> targets, boolean disableCaching)
      throws IOException, InterruptedException {
    BuildCommand buildCommand =
        new BuildCommand(
            targets.stream().map(Object::toString).collect(ImmutableList.toImmutableList()));
    buildCommand.setKeepGoing(true);
    return buildCommand.run(
        disableCaching ? params.withArtifactCacheFactory(new NoopArtifactCacheFactory()) : params);
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

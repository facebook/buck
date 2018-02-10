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

package com.facebook.buck.shell;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.WriteToFileArg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.rules.macros.WorkerMacroArg;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxProperties;
import com.facebook.buck.shell.AbstractGenruleStep.CommandString;
import com.facebook.buck.shell.programrunner.DirectProgramRunner;
import com.facebook.buck.shell.programrunner.ProgramRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.worker.WorkerJobParams;
import com.facebook.buck.worker.WorkerProcessIdentity;
import com.facebook.buck.worker.WorkerProcessParams;
import com.facebook.buck.worker.WorkerProcessPoolFactory;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Build rule for generating a file via a shell command. For example, to generate the katana
 * AndroidManifest.xml from the wakizashi AndroidManifest.xml, such a rule could be defined as:
 *
 * <pre>
 * genrule(
 *   name = 'katana_manifest',
 *   srcs = [
 *     'wakizashi_to_katana_manifest.py',
 *     'AndroidManifest.xml',
 *   ],
 *   cmd = 'python wakizashi_to_katana_manifest.py ${SRCDIR}/AndroidManfiest.xml &gt; $OUT',
 *   out = 'AndroidManifest.xml',
 * )
 * </pre>
 *
 * The output of this rule would likely be used as follows:
 *
 * <pre>
 * android_binary(
 *   name = 'katana',
 *   manifest = ':katana_manifest',
 *   deps = [
 *     # Additional dependent android_library rules would be listed here, as well.
 *   ],
 * )
 * </pre>
 *
 * A <code>genrule</code> is evaluated by running the shell command specified by {@code cmd} with
 * the following environment variable substitutions:
 *
 * <ul>
 *   <li><code>SRCS</code> will be a space-delimited string expansion of the <code>srcs</code>
 *       attribute where each element of <code>srcs</code> will be translated into an absolute path.
 *   <li><code>SRCDIR</code> will be a directory containing all files mentioned in the srcs.
 *   <li><code>OUT</code> is the output file for the <code>genrule()</code>. The file specified by
 *       this variable must always be written by this command. If not, the execution of this rule
 *       will be considered a failure, halting the build process.
 * </ul>
 *
 * In the above example, if the {@code katana_manifest} rule were defined in the {@code
 * src/com/facebook/wakizashi} directory, then the command that would be executed would be:
 *
 * <pre>
 * python convert_to_katana.py src/com/facebook/wakizashi/AndroidManifest.xml &gt; \
 *     buck-out/gen/src/com/facebook/wakizashi/AndroidManifest.xml
 * </pre>
 *
 * Note that {@code cmd} could be run on either Mac or Linux, so it should contain logic that works
 * on either platform. If this becomes an issue in the future (or we want to support building on
 * different platforms), then we could introduce a new attribute that is a map of target platforms
 * to the appropriate build command for that platform.
 *
 * <p>Note that the <code>SRCDIR</code> is populated by symlinking the sources.
 */
public class Genrule extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasOutputName, SupportsInputBasedRuleKey {

  /**
   * The order in which elements are specified in the {@code srcs} attribute of a genrule matters.
   */
  @AddToRuleKey protected final ImmutableList<SourcePath> srcs;

  @AddToRuleKey protected final Optional<Arg> cmd;
  @AddToRuleKey protected final Optional<Arg> bash;
  @AddToRuleKey protected final Optional<Arg> cmdExe;

  @AddToRuleKey private final String out;
  @AddToRuleKey private final String type;
  @AddToRuleKey private final boolean enableSandboxingInGenrule;
  @AddToRuleKey private final boolean isCacheable;
  @AddToRuleKey private final String environmentExpansionSeparator;

  private final BuildRuleResolver buildRuleResolver;
  private final SandboxExecutionStrategy sandboxExecutionStrategy;
  protected final Path pathToOutDirectory;
  protected final Path pathToOutFile;
  private final Path pathToTmpDirectory;
  private final Path pathToSrcDirectory;
  private final Boolean isWorkerGenrule;
  private final Optional<AndroidPlatformTarget> androidPlatformTarget;
  private final Optional<AndroidNdk> androidNdk;
  private final Optional<AndroidSdkLocation> androidSdkLocation;

  protected Genrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver buildRuleResolver,
      BuildRuleParams params,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      List<SourcePath> srcs,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> type,
      String out,
      boolean enableSandboxingInGenrule,
      boolean isCacheable,
      Optional<String> environmentExpansionSeparator,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<AndroidNdk> androidNdk,
      Optional<AndroidSdkLocation> androidSdkLocation) {
    super(buildTarget, projectFilesystem, params);
    this.androidPlatformTarget = androidPlatformTarget;
    this.androidNdk = androidNdk;
    this.buildRuleResolver = buildRuleResolver;
    this.sandboxExecutionStrategy = sandboxExecutionStrategy;
    this.srcs = ImmutableList.copyOf(srcs);
    this.cmd = cmd;
    this.bash = bash;
    this.cmdExe = cmdExe;

    this.out = out;
    this.pathToOutDirectory = BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "%s");
    this.pathToOutFile = this.pathToOutDirectory.resolve(out);
    this.isCacheable = isCacheable;
    this.environmentExpansionSeparator = environmentExpansionSeparator.orElse(" ");
    this.androidSdkLocation = androidSdkLocation;
    if (!pathToOutFile.startsWith(pathToOutDirectory) || pathToOutFile.equals(pathToOutDirectory)) {
      throw new HumanReadableException(
          "The 'out' parameter of genrule %s is '%s', which is not a valid file name.",
          buildTarget, out);
    }

    this.pathToTmpDirectory =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s__tmp");

    this.pathToSrcDirectory =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s__srcs");
    this.type = super.getType() + (type.isPresent() ? "_" + type.get() : "");
    this.isWorkerGenrule = this.isWorkerGenrule();
    this.enableSandboxingInGenrule = enableSandboxingInGenrule;
  }

  /** @return the absolute path to the output file */
  @VisibleForTesting
  public Path getAbsoluteOutputFilePath() {
    return getProjectFilesystem().resolve(pathToOutFile);
  }

  @VisibleForTesting
  public ImmutableList<SourcePath> getSrcs() {
    return srcs;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), pathToOutFile);
  }

  protected void addEnvironmentVariables(
      SourcePathResolver pathResolver, Builder<String, String> environmentVariablesBuilder) {
    environmentVariablesBuilder.put(
        "SRCS",
        srcs.stream()
            .map(pathResolver::getAbsolutePath)
            .map(Object::toString)
            .collect(Collectors.joining(this.environmentExpansionSeparator)));
    environmentVariablesBuilder.put("OUT", getAbsoluteOutputFilePath().toString());

    environmentVariablesBuilder.put(
        "GEN_DIR",
        getProjectFilesystem()
            .resolve(getProjectFilesystem().getBuckPaths().getGenDir())
            .toString());
    environmentVariablesBuilder.put(
        "SRCDIR", getProjectFilesystem().resolve(pathToSrcDirectory).toString());
    environmentVariablesBuilder.put(
        "TMP", getProjectFilesystem().resolve(pathToTmpDirectory).toString());

    // TODO(mbolin): This entire hack needs to be removed. The [tools] section of .buckconfig
    // should be generalized to specify local paths to tools that can be used in genrules.

    androidSdkLocation.ifPresent(
        sdk -> environmentVariablesBuilder.put("ANDROID_HOME", sdk.getSdkRootPath().toString()));
    androidNdk.ifPresent(
        ndk -> environmentVariablesBuilder.put("NDK_HOME", ndk.getNdkRootPath().toString()));
    androidPlatformTarget.ifPresent(
        target -> {
          environmentVariablesBuilder.put("DX", target.getDxExecutable().toString());
          environmentVariablesBuilder.put("ZIPALIGN", target.getZipalignExecutable().toString());
        });

    // TODO(t5302074): This shouldn't be necessary. Speculatively disabling.
    environmentVariablesBuilder.put("NO_BUCKD", "1");
  }

  @VisibleForTesting
  public boolean isWorkerGenrule() {
    Arg cmdArg = cmd.orElse(null);
    Arg bashArg = bash.orElse(null);
    Arg cmdExeArg = cmdExe.orElse(null);
    if ((cmdArg instanceof WorkerMacroArg)
        || (bashArg instanceof WorkerMacroArg)
        || (cmdExeArg instanceof WorkerMacroArg)) {
      if ((cmdArg != null && !(cmdArg instanceof WorkerMacroArg))
          || (bashArg != null && !(bashArg instanceof WorkerMacroArg))
          || (cmdExeArg != null && !(cmdExeArg instanceof WorkerMacroArg))) {
        throw new HumanReadableException(
            "You cannot use a worker macro in one of the cmd, bash, "
                + "or cmd_exe properties and not in the others for genrule %s.",
            getBuildTarget().getFullyQualifiedName());
      }
      return true;
    }
    return false;
  }

  @Override
  public String getType() {
    return type;
  }

  public AbstractGenruleStep createGenruleStep(BuildContext context) {
    SourcePathResolver sourcePathResolver = context.getSourcePathResolver();

    // The user's command (this.cmd) should be run from the directory that contains only the
    // symlinked files. This ensures that the user can reference only the files that were declared
    // as srcs. Without this, a genrule is not guaranteed to be hermetic.

    ProgramRunner programRunner;

    if (sandboxExecutionStrategy.isSandboxEnabled() && enableSandboxingInGenrule) {
      programRunner =
          sandboxExecutionStrategy.createSandboxProgramRunner(
              createSandboxConfiguration(sourcePathResolver));
    } else {
      programRunner = new DirectProgramRunner();
    }

    return new AbstractGenruleStep(
        getProjectFilesystem(),
        getBuildTarget(),
        new CommandString(
            Arg.flattenToSpaceSeparatedString(cmd, sourcePathResolver),
            Arg.flattenToSpaceSeparatedString(bash, sourcePathResolver),
            Arg.flattenToSpaceSeparatedString(cmdExe, sourcePathResolver)),
        BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToSrcDirectory)
            .getPathRelativeToBuildCellRoot(),
        programRunner) {
      @Override
      protected void addEnvironmentVariables(
          ExecutionContext executionContext, Builder<String, String> environmentVariablesBuilder) {
        Genrule.this.addEnvironmentVariables(sourcePathResolver, environmentVariablesBuilder);
      }
    };
  }

  private SandboxProperties createSandboxConfiguration(SourcePathResolver sourcePathResolver) {
    SandboxProperties.Builder builder = SandboxProperties.builder();
    return builder
        .addAllAllowedToReadPaths(
            srcs.stream()
                .map(sourcePathResolver::getAbsolutePath)
                .map(Object::toString)
                .collect(Collectors.toList()))
        .addAllAllowedToReadPaths(collectReadablePathsFromArguments(sourcePathResolver))
        .addAllowedToReadPaths(
            getProjectFilesystem().resolve(pathToSrcDirectory).toString(),
            getProjectFilesystem().resolve(pathToTmpDirectory).toString(),
            getProjectFilesystem().resolve(pathToOutDirectory).toString(),
            getProjectFilesystem()
                .resolve(WriteToFileArg.getMacroPath(getProjectFilesystem(), getBuildTarget()))
                .toString())
        .addAllowedToReadMetadataPaths(
            getProjectFilesystem().getRootPath().toAbsolutePath().toString())
        .addAllowedToWritePaths(
            getProjectFilesystem().resolve(pathToTmpDirectory).toString(),
            getProjectFilesystem().resolve(pathToOutDirectory).toString())
        .addDeniedToReadPaths(getProjectFilesystem().getRootPath().toAbsolutePath().toString())
        .build();
  }

  private ImmutableList<String> collectReadablePathsFromArguments(SourcePathResolver resolver) {
    ImmutableList.Builder<String> paths = ImmutableList.builder();
    cmd.ifPresent(c -> paths.addAll(collectExistingArgInputs(resolver, c)));
    if (Platform.detect() == Platform.WINDOWS) {
      cmdExe.ifPresent(c -> paths.addAll(collectExistingArgInputs(resolver, c)));
    } else {
      bash.ifPresent(c -> paths.addAll(collectExistingArgInputs(resolver, c)));
    }
    return paths.build();
  }

  private ImmutableList<String> collectExistingArgInputs(
      SourcePathResolver sourcePathResolver, Arg arg) {
    Collection<BuildRule> buildRules =
        BuildableSupport.getDepsCollection(arg, new SourcePathRuleFinder(buildRuleResolver));
    ImmutableList.Builder<String> inputs = ImmutableList.builder();
    for (BuildRule buildRule : buildRules) {
      SourcePath inputPath = buildRule.getSourcePathToOutput();
      if (inputPath != null) {
        inputs.add(sourcePathResolver.getAbsolutePath(inputPath).toString());
      }
    }
    return inputs.build();
  }

  public WorkerShellStep createWorkerShellStep(BuildContext context) {
    return new WorkerShellStep(
        getBuildTarget(),
        convertToWorkerJobParams(context.getSourcePathResolver(), cmd),
        convertToWorkerJobParams(context.getSourcePathResolver(), bash),
        convertToWorkerJobParams(context.getSourcePathResolver(), cmdExe),
        new WorkerProcessPoolFactory(getProjectFilesystem())) {
      @Override
      protected ImmutableMap<String, String> getEnvironmentVariables() {
        Builder<String, String> envVarBuilder = ImmutableMap.builder();
        Genrule.this.addEnvironmentVariables(context.getSourcePathResolver(), envVarBuilder);
        return envVarBuilder.build();
      }
    };
  }

  private static Optional<WorkerJobParams> convertToWorkerJobParams(
      SourcePathResolver resolver, Optional<Arg> arg) {
    return arg.map(
        arg1 -> {
          WorkerMacroArg workerMacroArg = (WorkerMacroArg) arg1;
          return WorkerJobParams.of(
              workerMacroArg.getJobArgs(resolver),
              WorkerProcessParams.of(
                  workerMacroArg.getTempDir(),
                  workerMacroArg.getStartupCommand(),
                  workerMacroArg.getEnvironment(),
                  workerMacroArg.getMaxWorkers(),
                  workerMacroArg.getPersistentWorkerKey().isPresent()
                      ? Optional.of(
                          WorkerProcessIdentity.of(
                              workerMacroArg.getPersistentWorkerKey().get(),
                              workerMacroArg.getWorkerHash()))
                      : Optional.empty()));
        });
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    buildableContext.recordArtifact(pathToOutFile);
    return getBuildStepsWithoutRecordingOutput(context);
  }

  /**
   * Return build steps, but don't record outputs to a {@link BuildableContext}.
   *
   * <p>Only meant to be used by subclasses which further process the genrule's output and will
   * record their outputs independently.
   */
  protected ImmutableList<Step> getBuildStepsWithoutRecordingOutput(BuildContext context) {

    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Make sure that the directory to contain the output file exists, deleting any pre-existing
    // ones. Rules get output to a directory named after the base path, so we don't want to nuke
    // the entire directory.

    commands.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToOutDirectory)));
    // Delete the old temp directory

    commands.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToTmpDirectory)));
    // Create a directory to hold all the source files.

    commands.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), pathToSrcDirectory)));

    addSymlinkCommands(context, commands);

    // Create a shell command that corresponds to this.cmd.
    if (this.isWorkerGenrule) {
      commands.add(createWorkerShellStep(context));
    } else {
      commands.add(createGenruleStep(context));
    }

    if (MorePaths.getFileExtension(pathToOutFile).equals("zip")) {
      commands.add(ZipScrubberStep.of(getAbsoluteOutputFilePath()));
    }

    return commands.build();
  }

  @VisibleForTesting
  void addSymlinkCommands(BuildContext context, ImmutableList.Builder<Step> commands) {
    if (srcs.isEmpty()) {
      return;
    }
    Path basePath = getBuildTarget().getBasePath();

    Map<Path, Path> linksBuilder = new HashMap<>();
    // To preserve legacy behavior, we allow duplicate targets and just ignore all but the last.
    Set<Path> seenTargets = new HashSet<>();
    // Symlink all sources into the temp directory so that they can be used in the genrule.
    for (SourcePath src : Lists.reverse(srcs)) {
      Path relativePath = context.getSourcePathResolver().getRelativePath(src);
      Path absolutePath = context.getSourcePathResolver().getAbsolutePath(src);
      Path canonicalPath = absolutePath.normalize();

      // By the time we get this far, all source paths (the keys in the map) have been converted
      // to paths relative to the project root. We want the path relative to the build target, so
      // strip the base path.
      Path localPath;
      if (absolutePath.equals(canonicalPath)) {
        if (relativePath.startsWith(basePath) || getBuildTarget().isInCellRoot()) {
          localPath = MorePaths.relativize(basePath, relativePath);
        } else {
          localPath = canonicalPath.getFileName();
        }
      } else {
        localPath = relativePath;
      }

      Path target = getProjectFilesystem().relativize(absolutePath);
      if (!seenTargets.contains(target)) {
        seenTargets.add(target);
        linksBuilder.put(localPath, target);
      }
    }
    commands.add(
        new SymlinkTreeStep(
            "genrule_srcs",
            getProjectFilesystem(),
            pathToSrcDirectory,
            ImmutableSortedMap.copyOf(linksBuilder)));
  }

  /** Get the output name of the generated file, as listed in the BUCK file. */
  @Override
  public String getOutputName() {
    return out;
  }

  @VisibleForTesting
  public Optional<Arg> getCmd() {
    return cmd;
  }

  @Override
  public final boolean isCacheable() {
    return isCacheable;
  }
}

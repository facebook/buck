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

import com.facebook.buck.android.AndroidPlatformTarget;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.WorkerMacroArg;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.AbstractGenruleStep.CommandString;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
public class Genrule extends AbstractBuildRule implements HasOutputName, SupportsInputBasedRuleKey {

  /**
   * The order in which elements are specified in the {@code srcs} attribute of a genrule matters.
   */
  @AddToRuleKey protected final ImmutableList<SourcePath> srcs;

  @AddToRuleKey protected final Optional<Arg> cmd;
  @AddToRuleKey protected final Optional<Arg> bash;
  @AddToRuleKey protected final Optional<Arg> cmdExe;

  @AddToRuleKey private final String out;
  @AddToRuleKey private final String type;

  protected final Path pathToOutDirectory;
  protected final Path pathToOutFile;
  private final Path pathToTmpDirectory;
  private final Path absolutePathToTmpDirectory;
  private final Path pathToSrcDirectory;
  private final Path absolutePathToSrcDirectory;
  private final Boolean isWorkerGenrule;

  protected Genrule(
      BuildRuleParams params,
      List<SourcePath> srcs,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> type,
      String out) {
    super(params);
    this.srcs = ImmutableList.copyOf(srcs);
    this.cmd = cmd;
    this.bash = bash;
    this.cmdExe = cmdExe;

    this.out = out;
    BuildTarget target = params.getBuildTarget();
    this.pathToOutDirectory = BuildTargets.getGenPath(getProjectFilesystem(), target, "%s");
    this.pathToOutFile = this.pathToOutDirectory.resolve(out);
    if (!pathToOutFile.startsWith(pathToOutDirectory) || pathToOutFile.equals(pathToOutDirectory)) {
      throw new HumanReadableException(
          "The 'out' parameter of genrule %s is '%s', which is not a valid file name.",
          params.getBuildTarget(), out);
    }

    this.pathToTmpDirectory =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s__tmp");
    this.absolutePathToTmpDirectory = getProjectFilesystem().resolve(pathToTmpDirectory);

    this.pathToSrcDirectory =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s__srcs");
    this.absolutePathToSrcDirectory = getProjectFilesystem().resolve(pathToSrcDirectory);
    this.type = super.getType() + (type.isPresent() ? "_" + type.get() : "");
    this.isWorkerGenrule = this.isWorkerGenrule();
  }

  /** @return the absolute path to the output file */
  @VisibleForTesting
  public String getAbsoluteOutputFilePath(SourcePathResolver pathResolver) {
    return pathResolver.getAbsolutePath(getSourcePathToOutput()).toString();
  }

  @VisibleForTesting
  public ImmutableList<SourcePath> getSrcs() {
    return srcs;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), pathToOutFile);
  }

  protected void addEnvironmentVariables(
      SourcePathResolver pathResolver,
      ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    environmentVariablesBuilder.put(
        "SRCS",
        Joiner.on(' ')
            .join(
                FluentIterable.from(srcs)
                    .transform(pathResolver::getAbsolutePath)
                    .transform(Object::toString)));
    environmentVariablesBuilder.put("OUT", getAbsoluteOutputFilePath(pathResolver));

    environmentVariablesBuilder.put(
        "GEN_DIR",
        getProjectFilesystem()
            .resolve(getProjectFilesystem().getBuckPaths().getGenDir())
            .toString());
    environmentVariablesBuilder.put("SRCDIR", absolutePathToSrcDirectory.toString());
    environmentVariablesBuilder.put("TMP", absolutePathToTmpDirectory.toString());

    // TODO(mbolin): This entire hack needs to be removed. The [tools] section of .buckconfig
    // should be generalized to specify local paths to tools that can be used in genrules.
    AndroidPlatformTarget android;
    try {
      android = context.getAndroidPlatformTarget();
    } catch (HumanReadableException e) {
      android = null;
    }

    if (android != null) {
      Optional<Path> sdkDirectory = android.getSdkDirectory();
      if (sdkDirectory.isPresent()) {
        environmentVariablesBuilder.put("ANDROID_HOME", sdkDirectory.get().toString());
      }
      Optional<Path> ndkDirectory = android.getNdkDirectory();
      if (ndkDirectory.isPresent()) {
        environmentVariablesBuilder.put("NDK_HOME", ndkDirectory.get().toString());
      }

      environmentVariablesBuilder.put("DX", android.getDxExecutable().toString());
      environmentVariablesBuilder.put("ZIPALIGN", android.getZipalignExecutable().toString());
    }

    // TODO(t5302074): This shouldn't be necessary. Speculatively disabling.
    environmentVariablesBuilder.put("NO_BUCKD", "1");
  }

  private static Optional<String> flattenToSpaceSeparatedString(
      Optional<Arg> arg, SourcePathResolver pathResolver) {
    return arg.map((input1) -> Arg.stringifyList(input1, pathResolver))
        .map(input -> Joiner.on(' ').join(input));
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
    // The user's command (this.cmd) should be run from the directory that contains only the
    // symlinked files. This ensures that the user can reference only the files that were declared
    // as srcs. Without this, a genrule is not guaranteed to be hermetic.

    return new AbstractGenruleStep(
        getProjectFilesystem(),
        getBuildTarget(),
        new CommandString(
            flattenToSpaceSeparatedString(cmd, context.getSourcePathResolver()),
            flattenToSpaceSeparatedString(bash, context.getSourcePathResolver()),
            flattenToSpaceSeparatedString(cmdExe, context.getSourcePathResolver())),
        absolutePathToSrcDirectory) {
      @Override
      protected void addEnvironmentVariables(
          ExecutionContext executionContext,
          ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
        Genrule.this.addEnvironmentVariables(
            context.getSourcePathResolver(), executionContext, environmentVariablesBuilder);
      }
    };
  }

  public WorkerShellStep createWorkerShellStep(BuildContext context) {
    return new WorkerShellStep(
        convertToWorkerJobParams(cmd, context.getSourcePathResolver()),
        convertToWorkerJobParams(bash, context.getSourcePathResolver()),
        convertToWorkerJobParams(cmdExe, context.getSourcePathResolver()),
        new WorkerProcessPoolFactory(getProjectFilesystem())) {
      @Override
      protected ImmutableMap<String, String> getEnvironmentVariables(
          ExecutionContext executionContext) {
        ImmutableMap.Builder<String, String> envVarBuilder = ImmutableMap.builder();
        Genrule.this.addEnvironmentVariables(
            context.getSourcePathResolver(), executionContext, envVarBuilder);
        return envVarBuilder.build();
      }
    };
  }

  private static Optional<WorkerJobParams> convertToWorkerJobParams(
      Optional<Arg> arg, SourcePathResolver pathResolver) {
    return arg.map(
        arg1 -> {
          WorkerMacroArg workerMacroArg = (WorkerMacroArg) arg1;
          return WorkerJobParams.of(
              workerMacroArg.getJobArgs(),
              WorkerProcessParams.of(
                  workerMacroArg.getTempDir(),
                  workerMacroArg.getStartupCommand(),
                  workerMacroArg.getStartupArgs(pathResolver),
                  workerMacroArg.getEnvironment(),
                  workerMacroArg.getMaxWorkers(),
                  workerMacroArg.getPersistentWorkerKey(),
                  Optional.of(workerMacroArg.getWorkerHash())));
        });
  }

  @Override
  @VisibleForTesting
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Make sure that the directory to contain the output file exists, deleting any pre-existing
    // ones. Rules get output to a directory named after the base path, so we don't want to nuke
    // the entire directory.
    commands.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToOutDirectory));
    // Delete the old temp directory
    commands.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToTmpDirectory));
    // Create a directory to hold all the source files.
    commands.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), pathToSrcDirectory));

    addSymlinkCommands(context, commands);

    // Create a shell command that corresponds to this.cmd.
    if (this.isWorkerGenrule) {
      commands.add(createWorkerShellStep(context));
    } else {
      commands.add(createGenruleStep(context));
    }

    if (MorePaths.getFileExtension(pathToOutFile).equals("zip")) {
      commands.add(
          ZipScrubberStep.of(
              context.getSourcePathResolver().getAbsolutePath(getSourcePathToOutput())));
    }

    buildableContext.recordArtifact(pathToOutFile);
    return commands.build();
  }

  @VisibleForTesting
  void addSymlinkCommands(BuildContext context, ImmutableList.Builder<Step> commands) {
    Path basePath = getBuildTarget().getBasePath();

    // Symlink all sources into the temp directory so that they can be used in the genrule.
    for (SourcePath src : srcs) {
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

      Path destination = pathToSrcDirectory.resolve(localPath);
      commands.add(MkdirStep.of(getProjectFilesystem(), destination.getParent()));
      commands.add(
          SymlinkFileStep.builder()
              .setFilesystem(getProjectFilesystem())
              .setExistingFile(relativePath)
              .setDesiredLink(destination)
              .build());
    }
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
}

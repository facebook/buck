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
import com.facebook.buck.android.NoAndroidSdkException;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.AbstractGenruleStep.CommandString;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build rule for generating a file via a shell command. For example, to generate the katana
 * AndroidManifest.xml from the wakizashi AndroidManifest.xml, such a rule could be defined as:
 * <pre>
 * genrule(
 *   name = 'katana_manifest',
 *   srcs = [
 *     'wakizashi_to_katana_manifest.py',
 *     'AndroidManifest.xml',
 *   ],
 *   cmd = 'python wakizashi_to_katana_manifest.py ${SRCDIR}/AndroidManfiest.xml > $OUT',
 *   out = 'AndroidManifest.xml',
 * )
 * </pre>
 * The output of this rule would likely be used as follows:
 * <pre>
 * android_binary(
 *   name = 'katana',
 *   manifest = ':katana_manifest',
 *   deps = [
 *     # Additional dependent android_library rules would be listed here, as well.
 *   ],
 * )
 * </pre>
 * A <code>genrule</code> is evaluated by running the shell command specified by {@code cmd} with
 * the following environment variable substitutions:
 * <ul>
 *   <li><code>SRCS</code> will be a space-delimited string expansion of the <code>srcs</code>
 *       attribute where each element of <code>srcs</code> will be translated into an absolute path.
 *   <li><code>SRCDIR</code> will be a directory containing all files mentioned in the srcs.</li>
 *   <li><code>OUT</code> is the output file for the <code>genrule()</code>. The file specified by
 *       this variable must always be written by this command. If not, the execution of this rule
 *       will be considered a failure, halting the build process.
 * </ul>
 * In the above example, if the {@code katana_manifest} rule were defined in the
 * {@code src/com/facebook/wakizashi} directory, then the command that would be executed would be:
 * <pre>
 * python convert_to_katana.py src/com/facebook/wakizashi/AndroidManifest.xml > \
 *     buck-out/gen/src/com/facebook/wakizashi/AndroidManifest.xml
 * </pre>
 * Note that {@code cmd} could be run on either Mac or Linux, so it should contain logic that works
 * on either platform. If this becomes an issue in the future (or we want to support building on
 * different platforms), then we could introduce a new attribute that is a map of target platforms
 * to the appropriate build command for that platform.
 * <p>
 * Note that the <code>SRCDIR</code> is populated by symlinking the sources.
 */
public class Genrule extends AbstractBuildRule implements HasOutputName {

  /**
   * The order in which elements are specified in the {@code srcs} attribute of a genrule matters.
   */
  @AddToRuleKey
  protected final ImmutableList<SourcePath> srcs;

  protected final Function<String, String> macroExpander;
  @AddToRuleKey
  protected final Optional<String> cmd;
  @AddToRuleKey
  protected final Optional<String> bash;
  @AddToRuleKey
  protected final Optional<String> cmdExe;

  protected final Map<Path, Path> srcsToAbsolutePaths;

  @AddToRuleKey
  private final String out;
  protected final Path pathToOutDirectory;
  protected final Path pathToOutFile;
  private final Path pathToTmpDirectory;
  private final Path absolutePathToTmpDirectory;
  private final Path pathToSrcDirectory;
  private final Path absolutePathToSrcDirectory;
  protected final Function<Path, Path> relativeToAbsolutePathFunction;

  protected Genrule(
      BuildRuleParams params,
      SourcePathResolver resolver,
      List<SourcePath> srcs,
      Function<String, String> macroExpander,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      String out,
      final Function<Path, Path> relativeToAbsolutePathFunction) {
    super(params, resolver);
    this.srcs = ImmutableList.copyOf(srcs);
    this.macroExpander = macroExpander;
    this.cmd = cmd;
    this.bash = bash;
    this.cmdExe = cmdExe;
    this.srcsToAbsolutePaths = FluentIterable
        .from(srcs)
        .transform(resolver.getPathFunction())
        .toMap(new Function<Path, Path>() {
          @Override
          public Path apply(Path src) {
            return relativeToAbsolutePathFunction.apply(src);
          }
        });

    this.out = out;
    BuildTarget target = params.getBuildTarget();
    this.pathToOutDirectory = Paths.get(
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash());
    this.pathToOutFile = this.pathToOutDirectory.resolve(out);

    this.pathToTmpDirectory = Paths.get(
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        String.format("%s__tmp", target.getShortNameAndFlavorPostfix()));
    // TODO(simons): pathToTmpDirectory.toAbsolutePath() should be enough
    this.absolutePathToTmpDirectory = relativeToAbsolutePathFunction.apply(pathToTmpDirectory);

    this.pathToSrcDirectory = Paths.get(
        BuckConstant.GEN_DIR,
        target.getBasePathWithSlash(),
        String.format("%s__srcs", target.getShortNameAndFlavorPostfix()));
    // TODO(simons): And here.
    this.absolutePathToSrcDirectory = relativeToAbsolutePathFunction.apply(pathToSrcDirectory);

    this.relativeToAbsolutePathFunction = relativeToAbsolutePathFunction;
  }

  /** @return the absolute path to the output file */
  public String getAbsoluteOutputFilePath() {
    return relativeToAbsolutePathFunction.apply(getPathToOutputFile()).toString();
  }

  @VisibleForTesting
  public ImmutableCollection<Path> getSrcs() {
    return getResolver().filterInputsToCompareToOutput(srcs);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  public Path getPathToOutputFile() {
    return pathToOutFile;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  protected void addEnvironmentVariables(ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    environmentVariablesBuilder.put("SRCS", Joiner.on(' ').join(srcsToAbsolutePaths.values()));
    environmentVariablesBuilder.put("OUT", getAbsoluteOutputFilePath());

    final Set<String> depFiles = Sets.newHashSet();
    final Set<BuildRule> processedBuildRules = Sets.newHashSet();
    for (BuildRule dep : getDeps()) {
      transformNames(processedBuildRules, depFiles, dep);
    }

    environmentVariablesBuilder.put(
        "GEN_DIR", relativeToAbsolutePathFunction.apply(BuckConstant.GEN_PATH).toString());
    environmentVariablesBuilder.put("DEPS", Joiner.on(' ').skipNulls().join(depFiles));
    environmentVariablesBuilder.put("SRCDIR", absolutePathToSrcDirectory.toString());
    environmentVariablesBuilder.put("TMP", absolutePathToTmpDirectory.toString());

    // TODO(mbolin): This entire hack needs to be removed. The [tools] section of .buckconfig
    // should be generalized to specify local paths to tools that can be used in genrules.
    AndroidPlatformTarget android;
    try {
      android = context.getAndroidPlatformTarget();
    } catch (NoAndroidSdkException e) {
      android = null;
    }
    if (android != null) {
      environmentVariablesBuilder.put("DX", android.getDxExecutable().toString());
      environmentVariablesBuilder.put("ZIPALIGN", android.getZipalignExecutable().toString());
    }

    // TODO(user): This shouldn't be necessary. Speculatively disabling.
    environmentVariablesBuilder.put("NO_BUCKD", "1");
  }

  private void transformNames(Set<BuildRule> processedBuildRules,
                              Set<String> appendTo,
                              BuildRule rule) {
    if (processedBuildRules.contains(rule)) {
      return;
    }
    processedBuildRules.add(rule);

    Path output = rule.getPathToOutputFile();
    if (output != null) {
      // TODO(user): This is a giant hack and we should do away with $DEPS altogether.
      // There can be a lot of paths here and the filesystem location can be arbitrarily long.
      // We can easily hit the shell command character limit. What this does is find
      // BuckConstant.GEN_DIR (which should be the same for every path) and replaces
      // it with a shell variable. This way the character count is much lower when run
      // from the shell but anyone reading the environment variable will get the
      // full paths due to variable interpolation
      if (output.startsWith(BuckConstant.GEN_PATH)) {
        Path relativePath =
            output.subpath(BuckConstant.GEN_PATH.getNameCount(), output.getNameCount());
        appendTo.add("$GEN_DIR/" + relativePath);
      } else {
        appendTo.add(relativeToAbsolutePathFunction.apply(output).toString());
      }
    }

    for (BuildRule dep : rule.getDeps()) {
      transformNames(processedBuildRules, appendTo, dep);
    }
  }

  public AbstractGenruleStep createGenruleStep() {
    // The user's command (this.cmd) should be run from the directory that contains only the
    // symlinked files. This ensures that the user can reference only the files that were declared
    // as srcs. Without this, a genrule is not guaranteed to be hermetic.
    File workingDirectory = new File(absolutePathToSrcDirectory.toString());

    return new AbstractGenruleStep(
        getBuildTarget(),
        new CommandString(
            cmd.transform(macroExpander),
            bash.transform(macroExpander),
            cmdExe.transform(macroExpander)),
        workingDirectory) {
      @Override
      protected void addEnvironmentVariables(
          ExecutionContext context,
          ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
        Genrule.this.addEnvironmentVariables(context, environmentVariablesBuilder);
      }
    };
  }

  @Override
  @VisibleForTesting
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Delete the old output for this rule, if it exists.
    commands.add(new RmStep(getPathToOutputFile(), true /* shouldForceDeletion */));

    // Make sure that the directory to contain the output file exists. Rules get output to a
    // directory named after the base path, so we don't want to nuke the entire directory.
    commands.add(new MkdirStep(pathToOutDirectory));

    // Delete the old temp directory
    commands.add(new MakeCleanDirectoryStep(pathToTmpDirectory));
    // Create a directory to hold all the source files.
    commands.add(new MakeCleanDirectoryStep(pathToSrcDirectory));

    addSymlinkCommands(commands);

    // Create a shell command that corresponds to this.cmd.
    commands.add(createGenruleStep());

    buildableContext.recordArtifact(pathToOutFile);
    return commands.build();
  }

  @VisibleForTesting
  void addSymlinkCommands(ImmutableList.Builder<Step> commands) {
    String basePath = getBuildTarget().getBasePathWithSlash();
    int basePathLength = basePath.length();

    // Symlink all sources into the temp directory so that they can be used in the genrule.
    for (Map.Entry<Path, Path> entry : srcsToAbsolutePaths.entrySet()) {
      String localPath = entry.getKey().toString();

      Path canonicalPath;
      canonicalPath = MorePaths.absolutify(entry.getValue());

      // By the time we get this far, all source paths (the keys in the map) have been converted
      // to paths relative to the project root. We want the path relative to the build target, so
      // strip the base path.
      if (entry.getValue().equals(canonicalPath)) {
        if (localPath.startsWith(basePath)) {
          localPath = localPath.substring(basePathLength);
        } else {
          localPath = canonicalPath.getFileName().toString();
        }
      }

      Path destination = pathToSrcDirectory.resolve(localPath);
      commands.add(new MkdirAndSymlinkFileStep(entry.getKey(), destination));
    }
  }

  /**
   * Get the output name of the generated file, as listed in the BUCK file.
   */
  @Override
  public String getOutputName() {
    return out;
  }

}

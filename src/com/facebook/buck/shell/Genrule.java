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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.shell.AbstractGenruleStep.CommandString;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.MorePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
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
 *   manifest = genfile('AndroidManifest.xml'),
 *   deps = [
 *     ':katana_manifest',
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
public class Genrule extends DoNotUseAbstractBuildable implements Buildable {

  /**
   * The order in which elements are specified in the {@code srcs} attribute of a genrule matters.
   */
  protected final ImmutableList<String> srcs;

  protected final Optional<String> cmd;
  protected final Optional<String> bash;
  protected final Optional<String> cmdExe;

  protected final Map<String, Path> srcsToAbsolutePaths;

  protected final Path pathToOutDirectory;
  protected final Path pathToOutFile;
  private final Path pathToTmpDirectory;
  private final Path absolutePathToTmpDirectory;
  private final Path pathToSrcDirectory;
  private final Path absolutePathToSrcDirectory;
  protected final Function<String, Path> relativeToAbsolutePathFunction;

  protected Genrule(BuildRuleParams buildRuleParams,
      List<String> srcs,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      String out,
      Function<String, Path> relativeToAbsolutePathFunction) {
    super(buildRuleParams);
    this.srcs = ImmutableList.copyOf(srcs);
    this.cmd = Preconditions.checkNotNull(cmd);
    this.bash = Preconditions.checkNotNull(bash);
    this.cmdExe = Preconditions.checkNotNull(cmdExe);
    this.srcsToAbsolutePaths = Maps.toMap(srcs, relativeToAbsolutePathFunction);

    Preconditions.checkNotNull(out);
    this.pathToOutDirectory = Paths.get(
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePathWithSlash());
    this.pathToOutFile = this.pathToOutDirectory.resolve(out);

    this.pathToTmpDirectory = Paths.get(
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePathWithSlash(),
        String.format("%s__tmp", getBuildTarget().getShortName()));
    // TODO(simons): pathToTmpDirectory.toAbsolutePath() should be enough
    this.absolutePathToTmpDirectory = relativeToAbsolutePathFunction.apply(
        pathToTmpDirectory.toString());

    this.pathToSrcDirectory = Paths.get(
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePathWithSlash(),
        String.format("%s__srcs", getBuildTarget().getShortName()));
    // TODO(simons): And here.
    this.absolutePathToSrcDirectory = relativeToAbsolutePathFunction.apply(
        pathToSrcDirectory.toString());

    this.relativeToAbsolutePathFunction = relativeToAbsolutePathFunction;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.GENRULE;
  }

  /** @return the absolute path to the output file */
  public String getAbsoluteOutputFilePath() {
    return relativeToAbsolutePathFunction.apply(getPathToOutputFile()).toString();
  }

  @Override
  public ImmutableSortedSet<String> getInputsToCompareToOutput() {
    return ImmutableSortedSet.copyOf(srcs);
  }

  @Override
  public String getPathToOutputFile() {
    return pathToOutFile.toString();
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .set("srcs", srcs)
        .set("cmd", cmd)
        .set("bash", bash)
        .set("cmd_exe", cmdExe);
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
        "GEN_DIR", relativeToAbsolutePathFunction.apply(BuckConstant.GEN_DIR).toString());
    environmentVariablesBuilder.put("DEPS", Joiner.on(' ').skipNulls().join(depFiles));
    environmentVariablesBuilder.put("SRCDIR", absolutePathToSrcDirectory.toString());
    environmentVariablesBuilder.put("TMP", absolutePathToTmpDirectory.toString());

    Optional<AndroidPlatformTarget> optionalAndroid = context.getAndroidPlatformTargetOptional();
    if (optionalAndroid.isPresent()) {
      AndroidPlatformTarget android = optionalAndroid.get();

      environmentVariablesBuilder.put("DX", android.getDxExecutable().getAbsolutePath());
    }
  }

  private void transformNames(Set<BuildRule> processedBuildRules,
                              Set<String> appendTo,
                              BuildRule rule) {
    if (processedBuildRules.contains(rule)) {
      return;
    }
    processedBuildRules.add(rule);

    Buildable buildable = Preconditions.checkNotNull(rule.getBuildable());

    String output = buildable.getPathToOutputFile();
    if (output != null) {
      // TODO(mbolin): This is a giant hack and we should do away with $DEPS altogether.
      // There can be a lot of paths here and the filesystem location can be arbitrarily long.
      // We can easily hit the shell command character limit. What this does is find
      // BuckConstant.GEN_DIR (which should be the same for every path) and replaces
      // it with a shell variable. This way the character count is much lower when run
      // from the shell but anyone reading the environment variable will get the
      // full paths due to variable interpolation
      if (output.startsWith(BuckConstant.GEN_DIR)) {
        String relativePath = output.substring(BuckConstant.GEN_DIR.length());
        if (relativePath.charAt(0) != '/') {
          relativePath = "/" + relativePath;
        }
        appendTo.add("$GEN_DIR" + relativePath);
      } else {
        appendTo.add(relativeToAbsolutePathFunction.apply(output).toString());
      }
    }

    for (BuildRule dep : rule.getDeps()) {
      transformNames(processedBuildRules, appendTo, dep);
    }
  }

  @VisibleForTesting
  AbstractGenruleStep createGenruleStep() {
    // The user's command (this.cmd) should be run from the directory that contains only the
    // symlinked files. This ensures that the user can reference only the files that were declared
    // as srcs. Without this, a genrule is not guaranteed to be hermetic.
    File workingDirectory = new File(absolutePathToSrcDirectory.toString());

    return new AbstractGenruleStep(
        this, new CommandString(cmd, bash, cmdExe), getDeps(), workingDirectory) {
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
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
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

    return commands.build();
  }

  @VisibleForTesting
  void addSymlinkCommands(ImmutableList.Builder<Step> commands) {
    String basePath = getBuildTarget().getBasePathWithSlash();
    int basePathLength = basePath.length();

    // Symlink all sources into the temp directory so that they can be used in the genrule.
    for (Map.Entry<String, Path> entry : srcsToAbsolutePaths.entrySet()) {
      String localPath = entry.getKey();

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

      String destination = pathToSrcDirectory + "/" + localPath;
      commands.add(new MkdirAndSymlinkFileStep(entry.getKey(), destination));
    }
  }


  public static Builder newGenruleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<Genrule>
      implements SrcsAttributeBuilder {

    protected List<String> srcs = Lists.newArrayList();

    protected Optional<String> cmd;
    protected Optional<String> bash;
    protected Optional<String> cmdExe;

    protected String out;

    private Function<String, Path> relativeToAbsolutePathFunctionForTesting = null;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
      cmd = Optional.absent();
      bash = Optional.absent();
      cmdExe = Optional.absent();
    }

    @Override
    public Genrule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new Genrule(buildRuleParams,
          srcs,
          cmd,
          bash,
          cmdExe,
          out,
          getRelativeToAbsolutePathFunction(buildRuleParams));
    }

    protected Function<String, Path> getRelativeToAbsolutePathFunction(BuildRuleParams params) {
      return (relativeToAbsolutePathFunctionForTesting == null)
          ? params.getPathRelativizer()
          : relativeToAbsolutePathFunctionForTesting;
    }

    @Override
    public Builder addSrc(String src) {
      srcs.add(src);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      deps.add(dep);
      return this;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      this.buildTarget = Preconditions.checkNotNull(buildTarget);
      return this;
    }

    public Builder setCmd(Optional<String> cmd) {
      this.cmd = Preconditions.checkNotNull(cmd);
      return this;
    }

    public Builder setBash(Optional<String> bash) {
      this.bash = Preconditions.checkNotNull(bash);
      return this;
    }

    public Builder setCmdExe(Optional<String> cmdExe) {
      this.cmdExe = cmdExe;
      return this;
    }

    public Builder setOut(String out) {
      this.out = out;
      return this;
    }

    @VisibleForTesting
    public Builder setRelativeToAbsolutePathFunctionForTesting(
        Function<String, Path> relativeToAbsolutePathFunction) {
      this.relativeToAbsolutePathFunctionForTesting = relativeToAbsolutePathFunction;
      return this;
    }
  }
}

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
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public class Genrule extends AbstractCachingBuildRule {

  /**
   * The order in which elements are specified in the {@code srcs} attribute of a genrule matters.
   */
  protected final ImmutableList<String> srcs;

  protected final String cmd;

  protected final Map<String, String> srcsToAbsolutePaths;

  protected final String pathToOutDirectory;
  protected final String pathToOutFile;
  protected final String absolutePathToOutFile;
  private final String pathToTmpDirectory;
  private final String absolutePathToTmpDirectory;
  private final String pathToSrcDirectory;
  private final String absolutePathToSrcDirectory;
  protected final Function<String, String> relativeToAbsolutePathFunction;

  protected Genrule(BuildRuleParams buildRuleParams,
      List<String> srcs,
      String cmd,
      String out,
      Function<String, String> relativeToAbsolutePathFunction) {
    super(buildRuleParams);
    this.srcs = ImmutableList.copyOf(srcs);
    this.cmd = Preconditions.checkNotNull(cmd);
    this.srcsToAbsolutePaths = Maps.toMap(srcs, relativeToAbsolutePathFunction);

    Preconditions.checkNotNull(out);
    this.pathToOutDirectory = String.format("%s/%s",
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePathWithSlash());
    this.pathToOutFile = String.format("%s%s", pathToOutDirectory, out);
    this.absolutePathToOutFile = relativeToAbsolutePathFunction.apply(this.pathToOutFile);

    this.pathToTmpDirectory = String.format("%s/%s%s__tmp",
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePathWithSlash(),
        getBuildTarget().getShortName());
    this.absolutePathToTmpDirectory = relativeToAbsolutePathFunction.apply(pathToTmpDirectory);

    this.pathToSrcDirectory = String.format("%s/%s%s__srcs",
        BuckConstant.GEN_DIR,
        buildRuleParams.getBuildTarget().getBasePathWithSlash(),
        getBuildTarget().getShortName()
    );
    this.absolutePathToSrcDirectory = relativeToAbsolutePathFunction.apply(pathToSrcDirectory);

    this.relativeToAbsolutePathFunction = relativeToAbsolutePathFunction;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.GENRULE;
  }

  /** @return the absolute path to the output file */
  public String getAbsoluteOutputFilePath() {
    return absolutePathToOutFile;
  }

  @Override
  protected ImmutableSortedSet<String> getInputsToCompareToOutput() {
    return ImmutableSortedSet.copyOf(srcs);
  }

  @Override
  public String getPathToOutputFile() {
    return pathToOutFile;
  }

  @Override
  protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
    return super.appendToRuleKey(builder)
        .set("srcs", srcs)
        .set("cmd", cmd);
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
    environmentVariablesBuilder.put("DEPS", Joiner.on(' ').skipNulls().join(depFiles));
    environmentVariablesBuilder.put("SRCDIR", absolutePathToSrcDirectory);
    environmentVariablesBuilder.put("TMP", absolutePathToTmpDirectory);

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

    String output = rule.getPathToOutputFile();
    if (output != null) {
      appendTo.add(relativeToAbsolutePathFunction.apply(output));
    }

    for (BuildRule dep : rule.getDeps()) {
      transformNames(processedBuildRules, appendTo, dep);
    }
  }

  @Override
  @VisibleForTesting
  public List<Step> buildInternal(BuildContext context) throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    // Delete the old output for this rule, if it exists.
    commands.add(new RmStep(getPathToOutputFile(), true /* shouldForceDeletion */));

    // Make sure that the directory to contain the output file exists. Rules get output to a
    // directory named after the base path, so we don't want to nuke the entire directory.
    commands.add(new MkdirStep(pathToOutDirectory));

    // Delete the old temp directory
    commands.add(new MakeCleanDirectoryStep(pathToTmpDirectory));
    // Create a directory to hold all the source files.
    // TODO(simons): Actually execute the command from here.
    commands.add(new MakeCleanDirectoryStep(pathToSrcDirectory));

    addSymlinkCommands(commands);

    // Create a shell command that corresponds to this.cmd.

    commands.add(new ShellStep() {
      private String command;

      @Override
      public String getShortName() {
        return String.format("genrule");
      }

      @Override
      protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
        buildCmd(context.getProjectFilesystem(), cmd);
        return ImmutableList.of("/bin/bash", "-c", command);
      }

      @Override
      public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
        ImmutableMap.Builder<String, String> environmentVariablesBuilder = ImmutableMap.builder();

        addEnvironmentVariables(context, environmentVariablesBuilder);

        return environmentVariablesBuilder.build();
      }

      @Override
      protected boolean shouldPrintStdErr(ExecutionContext context) {
        return true;
      }

      private void buildCmd(ProjectFilesystem filesystem, String cmd) {
        if (command != null) {
          return;
        }
        command = replaceMatches(filesystem, cmd);
      }
    });

    return commands.build();
  }

  @VisibleForTesting
  void addSymlinkCommands(ImmutableList.Builder<Step> commands) {
    String basePath = getBuildTarget().getBasePathWithSlash();
    int basePathLength = basePath.length();

    // Symlink all sources into the temp directory so that they can be used in the genrule.
    for (Map.Entry<String, String> entry : srcsToAbsolutePaths.entrySet()) {
      String localPath = entry.getKey();

      String canonicalPath;
      try {
        canonicalPath = new File(entry.getValue()).getCanonicalPath();
      } catch (IOException e) {
        throw new HumanReadableException(
            "Unable to determine the canonical path for: %s. Does the file exist?", localPath);
      }

      // By the time we get this far, all source paths (the keys in the map) have been converted
      // to paths relative to the project root. We want the path relative to the build target, so
      // strip the base path.
      if (entry.getValue().equals(canonicalPath)) {
        if (localPath.startsWith(basePath)) {
          localPath = localPath.substring(basePathLength);
        } else {
          localPath = new File(canonicalPath).getName();
        }
      }

      String destination = pathToSrcDirectory + "/" + localPath;
      commands.add(new MkdirAndSymlinkFileStep(entry.getKey(), destination));
    }
  }

  /**
   * Matches either a relative or fully-qualified build target wrapped in <tt>${}</tt>, unless the
   * <code>$</code> is preceded by a backslash.
   *
   * Given the input: $(exe //foo:bar), capturing groups are
   * 1: $(exe //foo:bar)
   * 2: exe
   * 3: //foo:bar
   * 4: //foo
   * 5: :bar
   * If we match against $(location :bar), the capturing groups are:
   * 1: $(location :bar)
   * 2: location
   * 3: :bar
   * 4: null
   * 5: :bar
   */
  @VisibleForTesting
  static final Pattern BUILD_TARGET_PATTERN = Pattern.compile(
      // We want a negative lookbehind to ensure we don't have a '\$', which is why this starts off
      // in such an interesting way.
      "(?<!\\\\)(\\$\\((exe|location)\\s+((\\/\\/[^:]*)?(:[^\\)]+))\\))"
  );

  /**
   * @return the cmd with binary and location build targets interpolated as either commands or the
   * location of the outputs of those targets.
   */
  @VisibleForTesting
  String replaceMatches(ProjectFilesystem filesystem, String command) {
    Matcher matcher = BUILD_TARGET_PATTERN.matcher(command);
    StringBuffer buffer = new StringBuffer();
    Map<String, BuildRule> fullyQualifiedNameToBuildRule = null;
    while (matcher.find()) {
      if (fullyQualifiedNameToBuildRule == null) {
        fullyQualifiedNameToBuildRule = Maps.newHashMap();
        for (BuildRule dep : getDeps()) {
          fullyQualifiedNameToBuildRule.put(dep.getFullyQualifiedName(), dep);
        }
      }

      String buildTarget = matcher.group(3);
      String base = matcher.group(4);
      if (base == null) {
        // This is a relative build target, so make it fully qualified.
        buildTarget = String.format("//%s%s", this.getBuildTarget().getBasePath(), buildTarget);
      }
      BuildRule matchingRule = fullyQualifiedNameToBuildRule.get(buildTarget);
      if (matchingRule == null) {
        throw new HumanReadableException("No dep named %s for %s %s, cmd was %s",
            buildTarget, getType().getName(), getFullyQualifiedName(), cmd);
      }

      String replacement;
      switch (matcher.group(2)) {
        case "exe":
          replacement = getExecutableReplacementFrom(filesystem, command, matchingRule);
          break;

        case "location":
          replacement = getLocationReplacementFrom(filesystem, matchingRule);
          break;

        default:
          throw new HumanReadableException("Unable to determine replacement for '%s' in target %s",
              matcher.group(2), getFullyQualifiedName());
      }

      matcher.appendReplacement(buffer, replacement);
    }
    matcher.appendTail(buffer);
    return buffer.toString();
  }

  private String getLocationReplacementFrom(ProjectFilesystem filesystem, BuildRule matchingRule) {
    return filesystem.getPathRelativizer().apply(matchingRule.getPathToOutputFile());
  }

  /**
   * A build rule can be executable in one of two ways: either by being a file with the executable
   * bit set, or by the rule being a {@link BinaryBuildRule}.
   *
   * @param filesystem The project file system to resolve files with.
   * @param cmd The command being executed.
   * @param matchingRule The BuildRule which may or may not be an executable.
   * @return A string which can be inserted to cause matchingRule to be executed.
   */
  private String getExecutableReplacementFrom(
      ProjectFilesystem filesystem,
      String cmd,
      BuildRule matchingRule) {
    if (matchingRule instanceof BinaryBuildRule) {
      return ((BinaryBuildRule) matchingRule).getExecutableCommand(filesystem);
    }

    File output = filesystem.getFileForRelativePath(matchingRule.getPathToOutputFile());
    if (output != null && output.exists() && output.canExecute()) {
      return output.getAbsolutePath();
    }

    throw new HumanReadableException(
        "%s must correspond to a binary rule or file in %s for %s %s",
        matchingRule.getFullyQualifiedName(),
        cmd,
        getType().getName(),
        getFullyQualifiedName());
  }

  public static Builder newGenruleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<Genrule>
      implements SrcsAttributeBuilder {

    protected List<String> srcs = Lists.newArrayList();

    protected String cmd;

    protected String out;

    private Function<String, String> relativeToAbsolutePathFunctionForTesting = null;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public Genrule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new Genrule(buildRuleParams,
          srcs,
          cmd,
          out,
          getRelativeToAbsolutePathFunction(buildRuleParams));
    }

    protected Function<String, String> getRelativeToAbsolutePathFunction(BuildRuleParams params) {
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
      this.buildTarget = buildTarget;
      return this;
    }

    public Builder setCmd(String cmd) {
      this.cmd = cmd;
      return this;
    }

    public Builder setOut(String out) {
      this.out = out;
      return this;
    }

    @VisibleForTesting
    public Builder setRelativeToAbsolutePathFunctionForTesting(
        Function<String, String> relativeToAbsolutePathFunction) {
      this.relativeToAbsolutePathFunctionForTesting = relativeToAbsolutePathFunction;
      return this;
    }
  }
}

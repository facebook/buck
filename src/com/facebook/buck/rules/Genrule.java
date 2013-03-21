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

package com.facebook.buck.rules;

import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Functions;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
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
 *     buck-gen/src/com/facebook/wakizashi/AndroidManifest.xml
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
  protected final ImmutableSortedSet<String> srcs;

  protected final String cmd;

  protected final Map<String, String> srcsToAbsolutePaths;

  protected final String outDirectory;
  protected final String outAsAbsolutePath;
  protected final String tmpDirectory;
  private final String srcDirectory;
  protected final Function<String, String> relativeToAbsolutePathFunction;

  protected Genrule(CachingBuildRuleParams cachingBuildRuleParams,
      List<String> srcs,
      String cmd,
      String out,
      Function<String, String> relativeToAbsolutePathFunction) {
    super(cachingBuildRuleParams);
    this.srcs = ImmutableSortedSet.<String>naturalOrder().addAll(srcs).build();
    this.cmd = Preconditions.checkNotNull(cmd);
    this.srcsToAbsolutePaths = Maps.toMap(srcs, relativeToAbsolutePathFunction);

    Preconditions.checkNotNull(out);
    this.outDirectory = String.format("%s/%s",
        BuckConstant.GEN_DIR,
        cachingBuildRuleParams.getBuildTarget().getBasePathWithSlash());
    String outWithGenDirPrefix = String.format("%s%s", outDirectory, out);
    this.outAsAbsolutePath = relativeToAbsolutePathFunction.apply(outWithGenDirPrefix);

    String temp = String.format("%s/%s/%s__tmp",
        BuckConstant.GEN_DIR,
        cachingBuildRuleParams.getBuildTarget().getBasePath(),
        getBuildTarget().getShortName()
        );
    this.tmpDirectory = relativeToAbsolutePathFunction.apply(temp);

    String srcdir = String.format("%s/%s/%s__srcs",
        BuckConstant.GEN_DIR,
        cachingBuildRuleParams.getBuildTarget().getBasePath(),
        getBuildTarget().getShortName()
    );
    this.srcDirectory = relativeToAbsolutePathFunction.apply(srcdir);

    this.relativeToAbsolutePathFunction = relativeToAbsolutePathFunction;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.GENRULE;
  }

  /** @return the absolute path to the output file */
  public String getOutputFilePath() {
    return outAsAbsolutePath;
  }

  @Override
  protected ImmutableSortedSet<String> getInputsToCompareToOutput(BuildContext context) {
    return srcs;
  }

  @Override
  public File getOutput() {
    return new File(getOutputFilePath());
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("srcs", srcs)
        .set("cmd", cmd);
   }

  protected void addEnvironmentVariables(
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    environmentVariablesBuilder.put("SRCS", Joiner.on(' ').join(srcsToAbsolutePaths.values()));
    environmentVariablesBuilder.put("OUT", getOutputFilePath());

    final Set<String> depFiles = Sets.newHashSet();
    final Set<BuildRule> processedBuildRules = Sets.newHashSet();
    for (BuildRule dep : getDeps()) {
      transformNames(processedBuildRules, depFiles, dep);
    }
    environmentVariablesBuilder.put("DEPS", Joiner.on(' ').skipNulls().join(depFiles));
    environmentVariablesBuilder.put("SRCDIR", srcDirectory);
    environmentVariablesBuilder.put("TMP", tmpDirectory);
  }

  private void transformNames(Set<BuildRule> processedBuildRules,
                              Set<String> appendTo,
                              BuildRule rule) {
    if (processedBuildRules.contains(rule)) {
      return;
    }
    processedBuildRules.add(rule);

    File output = rule.getOutput();
    if (output != null) {
      appendTo.add(relativeToAbsolutePathFunction.apply(output.getPath()));
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
    commands.add(new RmStep(getOutputFilePath(), true /* shouldForceDeletion */));

    // Make sure that the directory to contain the output file exists. Rules get output to a
    // directory named after the base path, so we don't want to nuke the entire directory.
    commands.add(new MkdirStep(outDirectory));

    // Delete the old temp directory
    commands.add(new MakeCleanDirectoryStep(tmpDirectory));
    // Create a directory to hold all the source files.
    // TODO(simons): Actually execute the command from here.
    commands.add(new MakeCleanDirectoryStep(srcDirectory));

    addSymlinkCommands(commands);

    // Create a shell command that corresponds to this.cmd.
    final String cmd = replaceBinaryBuildRuleRefsInCmd();
    final ImmutableList<String> commandArgs = ImmutableList.of("/bin/bash", "-c", cmd);
    ImmutableMap.Builder<String, String> environmentVariablesBuilder = ImmutableMap.builder();

    addEnvironmentVariables(environmentVariablesBuilder);

    final ImmutableMap<String, String> environmentVariables = environmentVariablesBuilder.build();
    commands.add(new ShellStep() {
      @Override
      public String getShortName(ExecutionContext context) {
        return String.format("genrule: %s", cmd);
      }

      @Override
      protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
        return commandArgs;
      }

      @Override
      public ImmutableMap<String, String> getEnvironmentVariables() {
        return environmentVariables;
      }

      @Override
      protected boolean shouldPrintStdErr(ExecutionContext context) {
        return true;
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

      File destination = new File(srcDirectory, localPath);
      commands.add(new MkdirAndSymlinkFileStep(entry.getValue(), destination.getAbsolutePath()));
    }
  }

  /**
   * Matches either a relative or fully-qualified build target wrapped in <tt>${}</tt>, unless the
   * <code>$</code> is preceded by a backslash.
   */
  @VisibleForTesting
  static final Pattern BUILD_TARGET_PATTERN = Pattern.compile(
      "([^\\\\]?)(\\$\\{((\\/\\/|:)[^\\}]+)\\})");

  /**
   * @return the cmd with binary build targets interpolated as executable commands
   */
  @VisibleForTesting
  String replaceBinaryBuildRuleRefsInCmd() {
    Matcher matcher = BUILD_TARGET_PATTERN.matcher(cmd);
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
      String prefix = matcher.group(4);
      if (":".equals(prefix)) {
        // This is a relative build target, so make it fully qualified.
        buildTarget = String.format("//%s%s", this.getBuildTarget().getBasePath(), buildTarget);
      }
      BuildRule matchingRule = fullyQualifiedNameToBuildRule.get(buildTarget);
      if (matchingRule == null) {
        throw new HumanReadableException("No dep named %s for %s %s, cmd was %s",
            buildTarget, getType().getDisplayName(), getFullyQualifiedName(), cmd);
      }

      if (!(matchingRule instanceof BinaryBuildRule)) {
        throw new HumanReadableException("%s must correspond to a binary rule in %s for %s %s",
            buildTarget, cmd, getType().getDisplayName(), getFullyQualifiedName());
      }
      BinaryBuildRule binaryBuildRule = (BinaryBuildRule)matchingRule;

      // Note that matcher.group(1) is the non-backslash character that did not escape the dollar
      // sign, so we make sure that it does not get lost during the regex replacement.
      String replacement = matcher.group(1) + binaryBuildRule.getExecutableCommand();
      matcher.appendReplacement(buffer, replacement);
    }
    matcher.appendTail(buffer);
    return buffer.toString();
  }

  public static Builder newGenruleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractCachingBuildRuleBuilder
      implements SrcsAttributeBuilder {

    protected List<String> srcs = Lists.newArrayList();

    protected String cmd;

    protected String out;

    protected Function<String, String> relativeToAbsolutePathFunction =
        Functions.RELATIVE_TO_ABSOLUTE_PATH;

    @Override
    public Genrule build(Map<String, BuildRule> buildRuleIndex) {
      return new Genrule(createCachingBuildRuleParams(buildRuleIndex),
          srcs,
          cmd,
          out,
          relativeToAbsolutePathFunction);
    }

    @Override
    public Builder addSrc(String src) {
      srcs.add(src);
      return this;
    }

    @Override
    public Builder addDep(String dep) {
      deps.add(dep);
      return this;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      this.buildTarget = buildTarget;
      return this;
    }

    @Override
    public Builder setArtifactCache(ArtifactCache artifactCache) {
      this.artifactCache = artifactCache;
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
    public Builder setRelativeToAbsolutePathFunction(
        Function<String, String> relativeToAbsolutePathFunction) {
      this.relativeToAbsolutePathFunction = relativeToAbsolutePathFunction;
      return this;
    }
  }
}

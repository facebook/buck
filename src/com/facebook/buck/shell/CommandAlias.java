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

package com.facebook.buck.shell;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.modern.PlatformSerialization;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * A {@link BinaryBuildRule} that wraps other build rules, and can optionally add extra arguments,
 * environment variables, or run different tools per host {@link Platform}.
 */
public class CommandAlias extends NoopBuildRule implements BinaryBuildRule, HasRuntimeDeps {
  /**
   * A {@link BuildRule} that is run as command on all platforms unless overridden in {@link
   * #platformDelegates}.
   *
   * <p>If this is a {@link BinaryBuildRule}, the implementation uses {@link
   * BinaryBuildRule#getExecutableCommand(OutputLabel)}. For other build rule types, the output
   * returned by {@link BuildRule#getSourcePathToOutput()} is run (and has to be executable).
   *
   * <p>If empty, {@link BinaryBuildRule#getExecutableCommand(OutputLabel)} will return a {@link
   * Tool} that throws {@link UnsupportedPlatformException} when attempting to call any method on
   * it, unless the command is overridden for the current host {@link Platform}.
   */
  @AddToRuleKey private final Optional<BuildRule> genericDelegate;

  /** Overrides for {@link #genericDelegate} specific to different host {@link Platform}s. */
  @AddToRuleKey private final ImmutableSortedMap<Platform, BuildRule> platformDelegates;

  /** Additional arguments to pass to the command when running it. */
  @AddToRuleKey private final ImmutableList<Arg> args;

  /** Additional environment variables to set when running the command. */
  @AddToRuleKey private final ImmutableSortedMap<String, Arg> env;

  @Nullable private final BuildRule exe;
  private final Platform platform;

  public CommandAlias(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Optional<BuildRule> genericDelegate,
      ImmutableSortedMap<Platform, BuildRule> platformDelegates,
      ImmutableList<Arg> args,
      ImmutableSortedMap<String, Arg> env,
      Platform platform) {
    super(buildTarget, projectFilesystem);
    this.genericDelegate = genericDelegate;
    this.platformDelegates = platformDelegates;
    this.args = args;
    this.env = env;
    this.platform = platform;

    BuildRule exe = platformDelegates.get(platform);
    this.exe = exe != null ? exe : genericDelegate.orElse(null);
  }

  @Override
  public boolean inputBasedRuleKeyIsEnabled() {
    // NoopBuildRule implements SupportsInputBasedRuleKey, which cannot add BuildRule for rule keys.
    return false;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    Stream<BuildTarget> deps =
        Stream.of(
                exe != null ? Stream.of(exe) : Stream.<BuildRule>empty(),
                extractDepsFromArgs(args.stream(), buildRuleResolver),
                extractDepsFromArgs(env.values().stream(), buildRuleResolver))
            .flatMap(Function.identity())
            .map(BuildRule::getBuildTarget);
    return exe instanceof HasRuntimeDeps
        ? Stream.concat(deps, ((HasRuntimeDeps) exe).getRuntimeDeps(buildRuleResolver))
        : deps;
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    ImmutableSortedMap<Platform, Tool> platformTools =
        platformDelegates.entrySet().stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), Entry::getKey, e -> buildRuleAsTool(e.getValue())));

    return ImmutableCrossPlatformTool.of(
        genericDelegate
            .map(this::buildRuleAsTool)
            .orElseGet(() -> new UnsupportedPlatformTool(getBuildTarget(), platform)),
        platformTools,
        platform);
  }

  private Tool buildRuleAsTool(BuildRule rule) {
    CommandTool.Builder tool;
    if (rule instanceof BinaryBuildRule) {
      tool =
          new CommandTool.Builder(
              ((BinaryBuildRule) rule).getExecutableCommand(OutputLabel.defaultLabel()));
    } else {
      SourcePath output =
          Objects.requireNonNull(
              rule.getSourcePathToOutput(),
              String.format("Cannot run %s as command. It has no output.", rule.getBuildTarget()));
      tool = new CommandTool.Builder().addArg(SourcePathArg.of(output));
    }
    args.forEach(tool::addArg);
    env.forEach(tool::addEnv);
    return tool.build();
  }

  private static class UnsupportedPlatformTool implements Tool {

    @AddToRuleKey private final BuildTarget buildTarget;

    @CustomFieldBehavior(PlatformSerialization.class)
    private final Platform platform;

    private UnsupportedPlatformTool(BuildTarget buildTarget, Platform platform) {
      this.buildTarget = buildTarget;
      this.platform = platform;
    }

    @Override
    public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
      throw new UnsupportedPlatformException(buildTarget, platform);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
      throw new UnsupportedPlatformException(buildTarget, platform);
    }
  }

  private Stream<BuildRule> extractDepsFromArgs(Stream<Arg> args, SourcePathRuleFinder ruleFinder) {
    return args.flatMap(arg -> BuildableSupport.deriveDeps(arg, ruleFinder));
  }

  /**
   * Specific runtime exception type that is thrown when trying to run a tool via {@link
   * CommandAlias} on a platform that has not been provided.
   */
  public static class UnsupportedPlatformException extends HumanReadableException {
    public UnsupportedPlatformException(BuildTarget target, Platform unsupportedPlatform) {
      super(
          "%s can not be run on %s",
          target.getFullyQualifiedName(), unsupportedPlatform.getPrintableName());
    }
  }

  /**
   * A {@link Tool} that delegates to different underlying tools depending on the host {@link
   * Platform} it is run on.
   *
   * <p>When calculating rule keys, this tool yields the same value regardless of the host platform,
   * and - in consequence - the underlying tool it delegates to.
   */
  @BuckStyleValue
  abstract static class CrossPlatformTool implements Tool {

    @AddToRuleKey
    protected abstract Tool getGenericTool();

    @AddToRuleKey
    protected abstract ImmutableSortedMap<Platform, Tool> getPlatformTools();

    @CustomFieldBehavior(PlatformSerialization.class)
    protected abstract Platform getPlatform();

    private Tool getTool() {
      return getPlatformTools().getOrDefault(getPlatform(), getGenericTool());
    }

    @Override
    public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
      return getTool().getCommandPrefix(resolver);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
      return getTool().getEnvironment(resolver);
    }
  }
}

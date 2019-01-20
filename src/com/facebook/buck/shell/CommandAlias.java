/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.immutables.value.Value;

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
   * BinaryBuildRule#getExecutableCommand()}. For other build rule types, the output returned by
   * {@link BuildRule#getSourcePathToOutput()} is run (and has to be executable).
   *
   * <p>If empty, {@link #getExecutableCommand()} will return a {@link Tool} that throws {@link
   * UnsupportedPlatformException} when attempting to call any method on it, unless the command is
   * overridden for the current host {@link Platform}.
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
  public SortedSet<BuildRule> getBuildDeps() {
    // since CommandAlias wraps other build rules, nothing has to be built.
    return ImmutableSortedSet.of();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    Stream<BuildTarget> deps =
        Stream.of(
                exe != null ? Stream.of(exe) : Stream.<BuildRule>empty(),
                extractDepsFromArgs(args.stream(), ruleFinder),
                extractDepsFromArgs(env.values().stream(), ruleFinder))
            .flatMap(Function.identity())
            .map(BuildRule::getBuildTarget);
    return exe instanceof HasRuntimeDeps
        ? Stream.concat(deps, ((HasRuntimeDeps) exe).getRuntimeDeps(ruleFinder))
        : deps;
  }

  @Override
  public Tool getExecutableCommand() {
    ImmutableSortedMap<Platform, Tool> platformTools =
        platformDelegates
            .entrySet()
            .stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), Entry::getKey, e -> buildRuleAsTool(e.getValue())));

    return CrossPlatformTool.of(
        genericDelegate.map(this::buildRuleAsTool).orElseGet(UnsupportedPlatformTool::new),
        platformTools,
        platform);
  }

  private Tool buildRuleAsTool(BuildRule rule) {
    CommandTool.Builder tool;
    if (rule instanceof BinaryBuildRule) {
      tool = new CommandTool.Builder(((BinaryBuildRule) rule).getExecutableCommand());
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

  private class UnsupportedPlatformTool implements Tool {
    @Override
    public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
      throw new UnsupportedPlatformException(getBuildTarget(), platform);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
      throw new UnsupportedPlatformException(getBuildTarget(), platform);
    }
  }

  private Stream<BuildRule> extractDepsFromArgs(Stream<Arg> args, SourcePathRuleFinder ruleFinder) {
    return args.map(arg -> BuildableSupport.deriveDeps(arg, ruleFinder))
        .flatMap(Function.identity());
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
  @Value.Immutable
  @BuckStyleTuple
  abstract static class AbstractCrossPlatformTool implements Tool, AddsToRuleKey {
    @AddToRuleKey
    protected abstract Tool getGenericTool();

    @AddToRuleKey
    protected abstract ImmutableSortedMap<Platform, Tool> getPlatformTools();

    protected abstract Platform getPlatform();

    @Value.Derived
    protected Tool getTool() {
      return getPlatformTools().getOrDefault(getPlatform(), getGenericTool());
    }

    @Override
    public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
      return getTool().getCommandPrefix(resolver);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
      return getTool().getEnvironment(resolver);
    }
  }
}

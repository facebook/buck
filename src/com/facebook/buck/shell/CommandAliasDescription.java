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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractTool;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringWithMacrosArg;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

public class CommandAliasDescription implements Description<CommandAliasDescriptionArg> {

  private final ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      MACRO_EXPANDERS = ImmutableList.of(new LocationMacroExpander());
  private final Platform platform;

  public CommandAliasDescription(Platform platform) {
    this.platform = platform;
  }

  @Override
  public Class<CommandAliasDescriptionArg> getConstructorArgType() {
    return CommandAliasDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CommandAliasDescriptionArg args) {

    if (args.getPlatformExe().isEmpty() && !args.getExe().isPresent()) {
      throw new HumanReadableException(
          "%s must have either 'exe' or 'platform_exe' set", buildTarget.getFullyQualifiedName());
    }
    CommandTool.Builder toolBuilder =
        args.getPlatformExe().isEmpty()
            ? getTool(args.getExe().get(), resolver)
                .map(CommandTool.Builder::new)
                .orElseGet(() -> toolBuilder(args.getExe().get()))
            : new CommandTool.Builder(
                PlatformSpecificTool.create(
                    resolver, buildTarget, platform, args.getPlatformExe(), args.getExe()));

    for (StringWithMacros x : args.getArgs()) {
      toolBuilder.addArg(
          StringWithMacrosArg.of(
              x, MACRO_EXPANDERS, Optional.empty(), buildTarget, cellRoots, resolver));
    }

    for (Map.Entry<String, StringWithMacros> x : args.getEnv().entrySet()) {
      toolBuilder.addEnv(
          x.getKey(),
          StringWithMacrosArg.of(
              x.getValue(), MACRO_EXPANDERS, Optional.empty(), buildTarget, cellRoots, resolver));
    }

    CommandTool commandTool = toolBuilder.build();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    return new CommandAlias(
        buildTarget,
        projectFilesystem,
        params.withExtraDeps(ImmutableSortedSet.copyOf(commandTool.getDeps(ruleFinder))),
        commandTool);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCommandAliasDescriptionArg extends CommonDescriptionArg {
    ImmutableList<StringWithMacros> getArgs();

    Optional<BuildTarget> getExe();

    @Value.NaturalOrder
    ImmutableSortedMap<Platform, BuildTarget> getPlatformExe();

    ImmutableMap<String, StringWithMacros> getEnv();
  }

  private static CommandTool.Builder toolBuilder(BuildTarget exe) {
    return new CommandTool.Builder().addArg(SourcePathArg.of(DefaultBuildTargetSourcePath.of(exe)));
  }

  private static Optional<Tool> getTool(BuildTarget target, BuildRuleResolver resolver) {
    BuildRule rule = resolver.getRule(target);
    return rule instanceof BinaryBuildRule
        ? Optional.of(((BinaryBuildRule) rule).getExecutableCommand())
        : Optional.empty();
  }

  private static class PlatformSpecificTool implements AbstractTool {
    @AddToRuleKey private final Supplier<Tool> tool;
    @AddToRuleKey private final Optional<BuildTarget> genericExe;
    @AddToRuleKey private final ImmutableSortedMap<Platform, BuildTarget> platformExe;

    private PlatformSpecificTool(
        Supplier<Tool> toolSupplier,
        Optional<BuildTarget> genericExe,
        ImmutableSortedMap<Platform, BuildTarget> platformExe) {
      this.tool = toolSupplier;
      this.genericExe = genericExe;
      this.platformExe = platformExe;
    }

    @Override
    public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
      Tool tool;
      try {
        tool = this.tool.get();
      } catch (UnsupportedPlatformException e) {
        return ImmutableList.of();
      }
      return tool.getDeps(ruleFinder);
    }

    @Override
    public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
      return tool.get().getCommandPrefix(resolver);
    }

    @Override
    public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
      return tool.get().getEnvironment(resolver);
    }

    static Tool create(
        BuildRuleResolver resolver,
        BuildTarget buildTarget,
        Platform targetPlatform,
        ImmutableSortedMap<Platform, BuildTarget> platformExe,
        Optional<BuildTarget> genericExe) {

      Optional<BuildTarget> tool = genericExe;

      for (Map.Entry<Platform, BuildTarget> entry : platformExe.entrySet()) {
        if (entry.getKey() == targetPlatform) {
          tool = Optional.of(entry.getValue());
        }
      }

      return new PlatformSpecificTool(
          tool.map(t -> MoreSuppliers.memoize(() -> asTool(t, resolver)))
              .orElse(
                  () -> {
                    throw new UnsupportedPlatformException(buildTarget, targetPlatform);
                  }),
          genericExe,
          platformExe);
    }

    private static Tool asTool(BuildTarget exe, BuildRuleResolver resolver) {
      return getTool(exe, resolver).orElse(toolBuilder(exe).build());
    }
  }

  public static class UnsupportedPlatformException extends HumanReadableException {
    public UnsupportedPlatformException(BuildTarget target, Platform unsupportedPlatform) {
      super(
          "%s can not be run on %s",
          target.getFullyQualifiedName(), unsupportedPlatform.getPrintableName());
    }
  }
}

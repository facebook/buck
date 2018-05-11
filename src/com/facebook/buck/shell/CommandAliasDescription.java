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

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;
import java.util.Optional;
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
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CommandAliasDescriptionArg args) {

    if (args.getPlatformExe().isEmpty() && !args.getExe().isPresent()) {
      throw new HumanReadableException(
          "%s must have either 'exe' or 'platform_exe' set", buildTarget.getFullyQualifiedName());
    }

    ImmutableList.Builder<Arg> toolArgs = ImmutableList.builder();
    ImmutableSortedMap.Builder<String, Arg> toolEnv = ImmutableSortedMap.naturalOrder();

    BuildRuleResolver resolver = context.getBuildRuleResolver();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget, context.getCellPathResolver(), resolver, MACRO_EXPANDERS);

    for (StringWithMacros x : args.getArgs()) {
      toolArgs.add(macrosConverter.convert(x));
    }

    for (Map.Entry<String, StringWithMacros> x : args.getEnv().entrySet()) {
      toolEnv.put(x.getKey(), macrosConverter.convert(x.getValue()));
    }

    Optional<BuildRule> exe = args.getExe().map(resolver::getRule);
    ImmutableSortedMap.Builder<Platform, BuildRule> platformExe = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<Platform, BuildTarget> entry : args.getPlatformExe().entrySet()) {
      platformExe.put(entry.getKey(), resolver.getRule(entry.getValue()));
    }

    return new CommandAlias(
        buildTarget,
        context.getProjectFilesystem(),
        exe,
        platformExe.build(),
        toolArgs.build(),
        toolEnv.build(),
        platform);
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
}

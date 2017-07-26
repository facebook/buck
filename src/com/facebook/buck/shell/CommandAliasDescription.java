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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringWithMacrosArg;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class CommandAliasDescription implements Description<CommandAliasDescriptionArg> {

  private final ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      MACRO_EXPANDERS = ImmutableList.of(new LocationMacroExpander());

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
      CommandAliasDescriptionArg args)
      throws NoSuchBuildTargetException {

    BuildTarget exe = args.getExe();
    CommandTool.Builder tool =
        getAsBinaryRule(exe, resolver)
            .map(BinaryBuildRule::getExecutableCommand)
            .map(CommandTool.Builder::new)
            .orElseGet(
                () ->
                    new CommandTool.Builder()
                        .addArg(SourcePathArg.of(new DefaultBuildTargetSourcePath(exe))));

    for (StringWithMacros x : args.getArgs()) {
      tool.addArg(StringWithMacrosArg.of(x, MACRO_EXPANDERS, buildTarget, cellRoots, resolver));
    }

    for (Map.Entry<String, StringWithMacros> x : args.getEnv().entrySet()) {
      tool.addEnv(
          x.getKey(),
          StringWithMacrosArg.of(x.getValue(), MACRO_EXPANDERS, buildTarget, cellRoots, resolver));
    }

    return new CommandAlias(buildTarget, projectFilesystem, params, tool.build());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCommandAliasDescriptionArg extends CommonDescriptionArg {
    ImmutableList<StringWithMacros> getArgs();

    BuildTarget getExe();

    ImmutableMap<String, StringWithMacros> getEnv();
  }

  private static Optional<BinaryBuildRule> getAsBinaryRule(
      BuildTarget target, BuildRuleResolver resolver) {
    try {
      return resolver.getRuleOptionalWithType(target, BinaryBuildRule.class);
    } catch (Throwable e) {
      return Optional.empty();
    }
  }
}

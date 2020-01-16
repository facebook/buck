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

package com.facebook.buck.core.rules.tool;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.args.ArgFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A legacy {@link Tool} interface to {@link RunInfo} provider info objects. This ensures that rules
 * returning {@link RunInfo} can still be executed by legacy style build rules, and also the {@code
 * buck run} command.
 */
@BuckStyleValue
public abstract class RunInfoLegacyTool implements Tool {

  @AddToRuleKey
  public abstract RunInfo getRunInfo();

  public static RunInfoLegacyTool of(RunInfo runInfo) {
    return ImmutableRunInfoLegacyTool.of(runInfo);
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
    RunInfo runInfo = getRunInfo();
    ImmutableList.Builder<String> command =
        ImmutableList.builderWithExpectedSize(runInfo.args().getEstimatedArgsCount());
    runInfo
        .args()
        .getArgsAndFormatStrings()
        .map(
            argAndFormat ->
                ArgFactory.from(
                    argAndFormat.getObject(), argAndFormat.getPostStringificationFormatString()))
        .forEach(arg -> arg.appendToCommandLine(command::add, resolver));
    return command.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
    return getRunInfo().getEnvironmentVariables();
  }
}

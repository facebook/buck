/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImmutableBuildRuleType;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.Beta;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.util.HashMap;
import java.util.Map;

@Beta
public class XcodeWorkspaceConfigDescription
    implements Description<XcodeWorkspaceConfigDescription.Arg> {
  public static final BuildRuleType TYPE = ImmutableBuildRuleType.of("xcode_workspace_config");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> XcodeWorkspaceConfig createBuildRule(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {
    return new XcodeWorkspaceConfig(
        params,
        new SourcePathResolver(resolver),
        args.srcTarget.transform(resolver.getRuleFunction()),
        resolver.getAllRules(args.extraTests.get()),
        getWorkspaceNameFromArg(args),
        getActionConfigNamesFromArg(args));
  }

  public static String getWorkspaceNameFromArg(Arg arg) {
    if (arg.workspaceName.isPresent()) {
      return arg.workspaceName.get();
    } else if (arg.srcTarget.isPresent()) {
      return arg.srcTarget.get().getShortNameAndFlavorPostfix();
    } else {
      throw new HumanReadableException(
          "Either workspace_name or src_target is required for xcode_workspace_config");
    }
  }

  public static ImmutableMap<SchemeActionType, String> getActionConfigNamesFromArg(Arg arg) {
    // Start out with the default action config names..
    Map<SchemeActionType, String> newActionConfigNames = new HashMap<>(
        SchemeActionType.DEFAULT_CONFIG_NAMES);
    // And override them with any provided in the "action_config_names" map.
    newActionConfigNames.putAll(arg.actionConfigNames.get());

    return ImmutableMap.copyOf(newActionConfigNames);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<BuildTarget> srcTarget;
    public Optional<ImmutableSortedSet<BuildTarget>> extraTests;
    public Optional<String> workspaceName;
    public Optional<ImmutableMap<SchemeActionType, String>> actionConfigNames;
  }
}

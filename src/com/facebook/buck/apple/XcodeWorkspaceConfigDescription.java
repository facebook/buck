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

import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class XcodeWorkspaceConfigDescription
    implements Description<XcodeWorkspaceConfigDescriptionArg> {

  @Override
  public Class<XcodeWorkspaceConfigDescriptionArg> getConstructorArgType() {
    return XcodeWorkspaceConfigDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      XcodeWorkspaceConfigDescriptionArg args) {
    return new NoopBuildRuleWithDeclaredAndExtraDeps(
        buildTarget, context.getProjectFilesystem(), params);
  }

  public static String getWorkspaceNameFromArg(XcodeWorkspaceConfigDescriptionArg arg) {
    if (arg.getWorkspaceName().isPresent()) {
      return arg.getWorkspaceName().get();
    } else if (arg.getSrcTarget().isPresent()) {
      return arg.getSrcTarget().get().getShortName();
    } else {
      throw new HumanReadableException(
          "Either workspace_name or src_target is required for xcode_workspace_config");
    }
  }

  public static ImmutableMap<SchemeActionType, String> getActionConfigNamesFromArg(
      XcodeWorkspaceConfigDescriptionArg arg) {
    // Start out with the default action config names..
    Map<SchemeActionType, String> newActionConfigNames =
        new HashMap<>(SchemeActionType.DEFAULT_CONFIG_NAMES);
    // And override them with any provided in the "action_config_names" map.
    newActionConfigNames.putAll(arg.getActionConfigNames());

    return ImmutableMap.copyOf(newActionConfigNames);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractXcodeWorkspaceConfigDescriptionArg extends CommonDescriptionArg {
    Optional<BuildTarget> getSrcTarget();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExtraTests();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExtraTargets();

    Optional<String> getWorkspaceName();

    ImmutableMap<SchemeActionType, String> getActionConfigNames();

    @Value.NaturalOrder
    ImmutableSortedMap<String, BuildTarget> getExtraSchemes();

    Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>>
        getEnvironmentVariables();

    Optional<Boolean> getIsRemoteRunnable();

    Optional<String> getExplicitRunnablePath();

    Optional<XCScheme.LaunchAction.LaunchStyle> getLaunchStyle();
  }
}

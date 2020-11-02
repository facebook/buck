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

package com.facebook.buck.features.apple.common;

import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class XcodeWorkspaceConfigDescription
    implements DescriptionWithTargetGraph<XcodeWorkspaceConfigDescriptionArg> {

  @Override
  public Class<XcodeWorkspaceConfigDescriptionArg> getConstructorArgType() {
    return XcodeWorkspaceConfigDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
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
      Optional<XcodeWorkspaceConfigDescriptionArg> arg) {
    // Start out with the default action config names..
    Map<SchemeActionType, String> newActionConfigNames =
        new HashMap<>(SchemeActionType.DEFAULT_CONFIG_NAMES);
    // And override them with any provided in the "action_config_names" map.
    if (arg.isPresent()) {
      newActionConfigNames.putAll(arg.get().getActionConfigNames());
    }

    return ImmutableMap.copyOf(newActionConfigNames);
  }

  @RuleArg
  interface AbstractXcodeWorkspaceConfigDescriptionArg extends BuildRuleArg {
    Optional<BuildTarget> getSrcTarget();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExtraTests();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExtraTargets();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExtraShallowTargets();

    Optional<String> getWorkspaceName();

    ImmutableMap<SchemeActionType, String> getActionConfigNames();

    @Value.NaturalOrder
    ImmutableSortedMap<String, BuildTarget> getExtraSchemes();

    Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>>
        getEnvironmentVariables();

    Optional<ImmutableMap<SchemeActionType, ImmutableMap<String, String>>>
        getCommandLineArguments();

    Optional<String> getApplicationLanguage();

    Optional<String> getApplicationRegion();

    Optional<ImmutableMap<SchemeActionType, BuildTarget>> getExpandVariablesBasedOn();

    /**
     * Add value to scheme to indicate it will be used to work on an app extension. This should
     * cause Xcode to automatically begin debugging the extension when it's launched.
     */
    Optional<Boolean> getWasCreatedForAppExtension();

    Optional<Boolean> getIsRemoteRunnable();

    Optional<String> getExplicitRunnablePath();

    Optional<String> getNotificationPayloadFile();

    Optional<XCScheme.LaunchAction.WatchInterface> getWatchInterface();

    Optional<XCScheme.LaunchAction.LaunchStyle> getLaunchStyle();

    Optional<
            ImmutableMap<
                SchemeActionType, ImmutableMap<XCScheme.AdditionalActions, ImmutableList<String>>>>
        getAdditionalSchemeActions();
  }
}

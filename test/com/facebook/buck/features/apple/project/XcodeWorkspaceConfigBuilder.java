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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class XcodeWorkspaceConfigBuilder
    extends AbstractNodeBuilder<
        XcodeWorkspaceConfigDescriptionArg.Builder,
        XcodeWorkspaceConfigDescriptionArg,
        XcodeWorkspaceConfigDescription,
        BuildRule> {

  protected XcodeWorkspaceConfigBuilder(BuildTarget target) {
    super(new XcodeWorkspaceConfigDescription(), target);
  }

  public static XcodeWorkspaceConfigBuilder createBuilder(BuildTarget target) {
    return new XcodeWorkspaceConfigBuilder(target);
  }

  public XcodeWorkspaceConfigBuilder setSrcTarget(Optional<BuildTarget> srcTarget) {
    getArgForPopulating().setSrcTarget(srcTarget);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExtraTests(ImmutableSortedSet<BuildTarget> extraTests) {
    getArgForPopulating().setExtraTests(extraTests);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExtraTargets(ImmutableSortedSet<BuildTarget> extraTargets) {
    getArgForPopulating().setExtraTargets(extraTargets);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExtraShallowTargets(
      ImmutableSortedSet<BuildTarget> extraShallowTargets) {
    getArgForPopulating().setExtraShallowTargets(extraShallowTargets);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setWorkspaceName(Optional<String> workspaceName) {
    getArgForPopulating().setWorkspaceName(workspaceName);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExtraSchemes(
      ImmutableSortedMap<String, BuildTarget> extraSchemes) {
    getArgForPopulating().setExtraSchemes(extraSchemes);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExplicitRunnablePath(Optional<String> runnablePath) {
    getArgForPopulating().setExplicitRunnablePath(runnablePath);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setLaunchStyle(
      Optional<XCScheme.LaunchAction.LaunchStyle> launchStyle) {
    getArgForPopulating().setLaunchStyle(launchStyle);
    return this;
  }

  public XcodeWorkspaceConfigBuilder setAdditionalSchemeActions(
      Optional<
              ImmutableMap<
                  SchemeActionType,
                  ImmutableMap<XCScheme.AdditionalActions, ImmutableList<String>>>>
          additionalSchemeActions) {
    getArgForPopulating().setAdditionalSchemeActions(additionalSchemeActions);
    return this;
  }
}

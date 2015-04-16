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
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public class XcodeWorkspaceConfigBuilder
    extends AbstractNodeBuilder<XcodeWorkspaceConfigDescription.Arg> {

  protected XcodeWorkspaceConfigBuilder(BuildTarget target) {
    super(new XcodeWorkspaceConfigDescription(), target);
  }

  public static XcodeWorkspaceConfigBuilder createBuilder(BuildTarget target) {
    return new XcodeWorkspaceConfigBuilder(target);
  }

  public XcodeWorkspaceConfigBuilder setSrcTarget(Optional<BuildTarget> srcTarget) {
    arg.srcTarget = srcTarget;
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExtraTests(
      Optional<ImmutableSortedSet<BuildTarget>> extraTests) {
    arg.extraTests = extraTests;
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExtraTargets(
      Optional<ImmutableSortedSet<BuildTarget>> extraTargets) {
    arg.extraTargets = extraTargets;
    return this;
  }

  public XcodeWorkspaceConfigBuilder setWorkspaceName(Optional<String> workspaceName) {
    arg.workspaceName = workspaceName;
    return this;
  }

  public XcodeWorkspaceConfigBuilder setActionConfigNames(
      Optional<ImmutableMap<SchemeActionType, String>> actionConfigNames) {
    arg.actionConfigNames = actionConfigNames;
    return this;
  }

  public XcodeWorkspaceConfigBuilder setExtraSchemes(
      Optional<ImmutableSortedMap<String, BuildTarget>> extraSchemes) {
    arg.extraSchemes = extraSchemes;
    return this;
  }

}

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

package com.facebook.buck.rules;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/**
 * A {@link BuildRule} which has no output. This is used in the following ways:
 *
 * <ol>
 *   <li>When a target has multiple potential outputs (e.g. a CxxLibrary may be static or shared).
 *       Flavored versions of the target will actually do work (and be depended on) in the action
 *       graph. However, the target graph to action graph conversion assumes that every node in the
 *       target graph will have a corresponding node in the action graph, so we create a
 *       NoopBuildRuleWithDeclaredAndExtraDeps to keep to that constraint, even though the actual
 *       work is done by the flavored versions.
 *   <li>When a target has no output artifacts, but its exit code may be interesting. e.g. {@link
 *       TestRule}s may not have any build steps to perform, but have runTests Steps to run to
 *       determine their exit code.
 *   <li>When a target just forwards an existing file, e.g. for prebuilt library rules, or if all
 *       the work is actually done on a depending rule (e.g. Lua).
 * </ol>
 */
public abstract class NoopBuildRule extends AbstractBuildRule implements SupportsInputBasedRuleKey {

  public NoopBuildRule(BuildTarget buildTarget, ProjectFilesystem projectFilesystem) {
    super(buildTarget, projectFilesystem);
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Nullable
  @Override
  public final SourcePath getSourcePathToOutput() {
    return null;
  }

  // Avoid a round-trip to the cache, as noop rules have no output.
  @Override
  public final boolean isCacheable() {
    return false;
  }
}

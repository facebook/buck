/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.Collections;

import javax.annotation.Nullable;

/**
 * Wrapper buildable for an existing Xcode project target.
 *
 * Example:
 * <pre>
 *  xcode_native(
 *    name = 'SPDY',
 *    xcodeproj = 'spdy/SPDY/SPDY.xcodeproj',
 *  )
 * </pre>
 */
public class XcodeNative extends AbstractBuildRule {
  private final SourcePath projectContainerPath;
  private final String targetName;
  private final String buildableName;

  public XcodeNative(
      BuildRuleParams params,
      SourcePathResolver resolver,
      XcodeNativeDescription.Arg arg) {
    super(params, resolver);
    this.projectContainerPath = arg.projectContainerPath;

    String shortName = params.getBuildTarget().getShortNameOnly();
    this.targetName = arg.targetName.or(shortName);
    this.buildableName = arg.buildableName.or("lib" + shortName + ".a");
  }

  public SourcePath getProjectContainerPath() {
    return projectContainerPath;
  }

  public String getTargetName() {
    return targetName;
  }

  public String getBuildableName() {
    return buildableName;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    // This returns where the build product ends up residing, rather than where it comes from.
    return null;
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    // TODO(user): Somehow enumerate all files referenced by the xcode project.
    return getResolver().filterInputsToCompareToOutput(
        Collections.singleton(
            projectContainerPath));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    // TODO(user): The buck native implementation will likely call product to xcodebuild with
    // some set of xcode build settings, collect the build products (a bundle or archive) and copy
    // them to the generated files directory.
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }
}

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

import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Wrapper buildable for an existing Xcode project target.
 *
 * Example:
 * <pre>
 *  xcode_native(
 *    name = 'SPDY',
 *    xcodeproj = 'spdy/SPDY/SPDY.xcodeproj',
 *    gid = '3870AF5114E47F8E009D8118',
 *    product = 'libSPDY.a',
 *  )
 * </pre>
 */
public class XcodeNative extends AbstractBuildable {
  private final SourcePath projectContainerPath;
  private final String targetGid;
  private final String product;

  private final Path pathToOutputFile;

  public XcodeNative(final BuildRuleParams params, XcodeNativeDescription.Arg arg) {
    this.projectContainerPath = Preconditions.checkNotNull(arg.projectContainerPath);
    this.targetGid = Preconditions.checkNotNull(arg.targetGid);
    this.product = Preconditions.checkNotNull(arg.product);

    this.pathToOutputFile = Paths.get(
        BuckConstant.GEN_DIR, params.getBuildTarget().getBasePath(), product);
  }

  public SourcePath getProjectContainerPath() {
    return projectContainerPath;
  }

  public String getTargetGid() {
    return targetGid;
  }

  public String getProduct() {
    return product;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    // This returns where the build product ends up residing, rather than where it comes from.
    return pathToOutputFile;
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    // TODO(user): Somehow enumerate all files referenced by the xcode project.
    return SourcePaths.filterInputsToCompareToOutput(Collections.singleton(projectContainerPath));
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    // TODO(user): The buck native implementation will likely call product to xcodebuild with
    // some set of xcode build settings, collect the build products (a bundle or archive) and copy
    // them to the generated files directory.
    return ImmutableList.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("targetGid", targetGid)
        .set("product", product);
  }
}

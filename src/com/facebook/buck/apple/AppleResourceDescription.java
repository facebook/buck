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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

/** Description for an apple_resource rule which copies resource files to the built bundle. */
public class AppleResourceDescription
    implements Description<AppleResourceDescriptionArg>,
        Flavored,
        HasAppleBundleResourcesDescription<AppleResourceDescriptionArg> {

  @Override
  public Class<AppleResourceDescriptionArg> getConstructorArgType() {
    return AppleResourceDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleResourceDescriptionArg args) {
    return new NoopBuildRuleWithDeclaredAndExtraDeps(buildTarget, projectFilesystem, params);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @Override
  public void addAppleBundleResources(
      AppleBundleResources.Builder builder,
      TargetNode<AppleResourceDescriptionArg, ?> targetNode,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver) {
    AppleResourceDescriptionArg appleResource = targetNode.getConstructorArg();
    builder.addAllResourceDirs(appleResource.getDirs());
    builder.addAllResourceFiles(appleResource.getFiles());
    builder.addAllResourceVariantFiles(appleResource.getVariants());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAppleResourceDescriptionArg extends CommonDescriptionArg {
    ImmutableSet<SourcePath> getDirs();

    ImmutableSet<SourcePath> getFiles();

    ImmutableSet<SourcePath> getVariants();

    ImmutableSet<BuildTarget> getResourcesFromDeps();
  }
}

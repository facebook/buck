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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

/** Description for an apple_resource rule which copies resource files to the built bundle. */
public class AppleResourceDescription
    implements Description<AppleResourceDescription.Arg>,
        Flavored,
        HasAppleBundleResourcesDescription<AppleResourceDescription.Arg> {

  @Override
  public Class<Arg> getConstructorArgType() {
    return Arg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      Arg args) {
    return new NoopBuildRule(params);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @Override
  public void addAppleBundleResources(
      AppleBundleResources.Builder builder,
      TargetNode<Arg, ?> targetNode,
      ProjectFilesystem filesystem,
      BuildRuleResolver resolver) {
    Arg appleResource = targetNode.getConstructorArg();
    builder.addAllResourceDirs(appleResource.dirs);
    builder.addAllResourceFiles(appleResource.files);
    builder.addAllResourceVariantFiles(appleResource.variants);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Set<SourcePath> dirs;
    public Set<SourcePath> files;
    public Set<SourcePath> variants = ImmutableSet.of();
  }
}

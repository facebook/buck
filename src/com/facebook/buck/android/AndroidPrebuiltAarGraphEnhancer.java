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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

class AndroidPrebuiltAarGraphEnhancer {

  private static final Flavor AAR_UNZIP_FLAVOR = ImmutableFlavor.of("aar_unzip");

  /** Utility class: do not instantiate. */
  private AndroidPrebuiltAarGraphEnhancer() {}

  /**
   * Creates a build rule to unzip the prebuilt AAR and get the components needed for the
   * AndroidPrebuiltAar, PrebuiltJar, and AndroidResource
   */
  static UnzipAar enhance(
      BuildRuleParams originalBuildRuleParams,
      SourcePath aarFile,
      BuildRuleResolver ruleResolver) {
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    UnflavoredBuildTarget originalBuildTarget =
        originalBuildRuleParams.getBuildTarget().checkUnflavored();

    BuildRuleParams unzipAarParams = originalBuildRuleParams.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_UNZIP_FLAVOR),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
        Suppliers.ofInstance(ImmutableSortedSet.copyOf(
                resolver.filterBuildRuleInputs(aarFile))));
    UnzipAar unzipAar = new UnzipAar(unzipAarParams, resolver, aarFile);
    return ruleResolver.addToIndex(unzipAar);
  }

}

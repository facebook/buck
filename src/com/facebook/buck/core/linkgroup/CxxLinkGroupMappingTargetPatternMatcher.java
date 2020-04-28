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

package com.facebook.buck.core.linkgroup;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.annotations.VisibleForTesting;

/** Target matcher for build target pattern labels beginning with "pattern:". */
@BuckStyleValue
public abstract class CxxLinkGroupMappingTargetPatternMatcher
    implements CxxLinkGroupMappingTargetMatcher {

  @VisibleForTesting
  public abstract String getPattern();

  abstract BuildTargetMatcher getPatternMatcher();

  public static CxxLinkGroupMappingTargetPatternMatcher of(
      String pattern, BuildTargetMatcher patternMatcher) {
    return ImmutableCxxLinkGroupMappingTargetPatternMatcher.ofImpl(pattern, patternMatcher);
  }

  @Override
  public boolean matchesNode(TargetNode<?> node) {
    return getPatternMatcher().matches(node.getBuildTarget().getUnconfiguredBuildTarget());
  }

  @Override
  public int compareTo(CxxLinkGroupMappingTargetMatcher that) {
    if (this == that) {
      return 0;
    }

    if (that instanceof CxxLinkGroupMappingTargetLabelMatcher) {
      return this.getPattern()
          .compareTo(((CxxLinkGroupMappingTargetPatternMatcher) that).getPattern());
    }

    return this.getClass().getName().compareTo(that.getClass().getName());
  }
}

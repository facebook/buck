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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ComparisonChain;
import java.util.Optional;

/**
 * Represents how a single build target should be mapped to a link group.
 *
 * <p>When used in BUCK files, it would be expressed as:
 *
 * <pre>
 *   link_group_map = [
 *     ("...", [("//Some:Target", "tree")]),
 *   ],
 * </pre>
 *
 * In this case, {@link CxxLinkGroupMappingTarget} represents the tuple <code>
 * ("//Some:Target", "tree")</code>.
 */
@BuckStyleValue
public abstract class CxxLinkGroupMappingTarget implements Comparable<CxxLinkGroupMappingTarget> {

  /**
   * Defines how nodes should be included starting from the root as specified by the build target.
   */
  public enum Traversal {
    /** The target and all of its transitive dependencies are included in the link group. */
    TREE,
    /** The target is included in the link group. */
    NODE,
  }

  @AddToRuleKey
  public abstract BuildTarget getBuildTarget();

  @AddToRuleKey
  public abstract Traversal getTraversal();

  @AddToRuleKey
  public abstract Optional<CxxLinkGroupMappingTargetMatcher> getMatcher();

  public static CxxLinkGroupMappingTarget of(
      BuildTarget buildTarget,
      CxxLinkGroupMappingTarget.Traversal traversal,
      Optional<CxxLinkGroupMappingTargetMatcher> matcher) {
    return ImmutableCxxLinkGroupMappingTarget.ofImpl(buildTarget, traversal, matcher);
  }

  @Override
  public int compareTo(CxxLinkGroupMappingTarget that) {
    if (this == that) {
      return 0;
    }

    int matcherCompare = Optionals.compare(this.getMatcher(), that.getMatcher());
    if (matcherCompare != 0) {
      return matcherCompare;
    }

    return ComparisonChain.start()
        .compare(this.getBuildTarget(), that.getBuildTarget())
        .compare(this.getTraversal(), that.getTraversal())
        .result();
  }
}

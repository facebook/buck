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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

/**
 * Represents a single mapping which specifies which targets belong to a link group.
 *
 * <p>When used in BUCK files, it would be expressed as:
 *
 * <pre>
 *   link_group_map = [
 *     ("group_name", [mapping1, mapping2]),
 *   ],
 * </pre>
 *
 * In this case, {@link CxxLinkGroupMapping} represents the tuple <code>
 * ("group_name", [mapping1, mapping2])</code>. Each mapping (e.g., <code>mapping1</code>) is
 * represented by {@link CxxLinkGroupMappingTarget}.
 */
@BuckStyleValue
public abstract class CxxLinkGroupMapping implements Comparable<CxxLinkGroupMapping> {

  @AddToRuleKey
  public abstract String getLinkGroup();

  @AddToRuleKey
  public abstract ImmutableList<CxxLinkGroupMappingTarget> getMappingTargets();

  public static CxxLinkGroupMapping of(
      String linkGroup, ImmutableList<CxxLinkGroupMappingTarget> mappingTargets) {
    return ImmutableCxxLinkGroupMapping.of(linkGroup, mappingTargets);
  }

  @Override
  public int compareTo(CxxLinkGroupMapping that) {
    if (this == that) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(this.getLinkGroup(), that.getLinkGroup())
        .compare(
            this.getMappingTargets(),
            that.getMappingTargets(),
            Ordering.<CxxLinkGroupMappingTarget>natural().lexicographical())
        .result();
  }
}

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

package com.facebook.buck.cxx.toolchain.nativelink;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.util.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class NativeLinkableGroups {

  private NativeLinkableGroups() {}

  /**
   * @return a {@link Consumer} which accepts {@link BuildTarget}s and filter-casts them {@link
   *     NativeLinkableGroup}s.
   */
  public static Consumer<BuildTarget> filterConsumer(
      BuildRuleResolver resolver, Consumer<? super NativeLinkableGroup> consumer) {
    return target -> {
      BuildRule rule = resolver.getRule(target);
      if (rule instanceof NativeLinkableGroup) {
        consumer.accept((NativeLinkableGroup) rule);
      }
    };
  }

  /**
   * Find {@link NativeLinkableGroup} nodes transitively reachable from the given roots.
   *
   * @param from the starting set of roots to begin the search from.
   * @param passthrough a {@link Function} determining acceptable dependencies to traverse when
   *     searching for {@link NativeLinkableGroup}s.
   * @return all the roots found as a map from {@link BuildTarget} to {@link NativeLinkableGroup}.
   */
  public static <T> ImmutableMap<BuildTarget, NativeLinkableGroup> getNativeLinkableRoots(
      Iterable<? extends T> from,
      Function<? super T, Optional<Iterable<? extends T>>> passthrough) {
    ImmutableMap.Builder<BuildTarget, NativeLinkableGroup> nativeLinkables = ImmutableMap.builder();

    AbstractBreadthFirstTraversal<T> visitor =
        new AbstractBreadthFirstTraversal<T>(from) {
          @Override
          public Iterable<? extends T> visit(T rule) {

            // If this is a passthrough rule, just continue on to its deps.
            Optional<Iterable<? extends T>> deps = passthrough.apply(rule);
            if (deps.isPresent()) {
              return deps.get();
            }

            // If this is `NativeLinkable`, we've found a root so record the rule and terminate
            // the search.
            if (rule instanceof NativeLinkableGroup) {
              NativeLinkableGroup nativeLinkableGroup = (NativeLinkableGroup) rule;
              nativeLinkables.put(nativeLinkableGroup.getBuildTarget(), nativeLinkableGroup);
              return ImmutableSet.of();
            }

            // Otherwise, terminate the search.
            return ImmutableSet.of();
          }
        };
    visitor.start();

    return nativeLinkables.build();
  }

  public static Linker.LinkableDepType getLinkStyle(
      NativeLinkableGroup.Linkage preferredLinkage, Linker.LinkableDepType requestedLinkStyle) {
    Linker.LinkableDepType linkStyle;
    switch (preferredLinkage) {
      case SHARED:
        linkStyle = Linker.LinkableDepType.SHARED;
        break;
      case STATIC:
        linkStyle =
            requestedLinkStyle == Linker.LinkableDepType.STATIC
                ? Linker.LinkableDepType.STATIC
                : Linker.LinkableDepType.STATIC_PIC;
        break;
      case ANY:
        linkStyle = requestedLinkStyle;
        break;
      default:
        throw new IllegalStateException();
    }
    return linkStyle;
  }
}

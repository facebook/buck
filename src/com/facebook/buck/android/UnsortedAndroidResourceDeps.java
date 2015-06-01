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

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaTest;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleDependencyVisitors;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;

public class UnsortedAndroidResourceDeps {

  @SuppressWarnings("unchecked")
  private static final ImmutableSet<Class<? extends BuildRule>> TRAVERSABLE_TYPES = ImmutableSet.of(
      AndroidBinary.class,
      AndroidInstrumentationApk.class,
      AndroidLibrary.class,
      AndroidResource.class,
      ApkGenrule.class,
      JavaLibrary.class,
      JavaTest.class,
      RobolectricTest.class);

  public interface Callback {
    public void onRuleVisited(BuildRule rule, ImmutableSet<BuildRule> depsToVisit);
  }

  private final ImmutableSet<HasAndroidResourceDeps> resourceDeps;

  public UnsortedAndroidResourceDeps(ImmutableSet<HasAndroidResourceDeps> resourceDeps) {
    this.resourceDeps = resourceDeps;
  }

  public ImmutableSet<HasAndroidResourceDeps> getResourceDeps() {
    return resourceDeps;
  }

  /**
   * Returns transitive android resource deps which are _not_ sorted topologically, only to be used
   * when the order of the resource rules does not matter, for instance, when graph enhancing
   * UberRDotJava, DummyRDotJava, AaptPackageResources where we only need the deps to correctly
   * order the execution of those buildables.
   */
  public static UnsortedAndroidResourceDeps createFrom(
      Collection<BuildRule> rules,
      final Optional<Callback> callback) {

    final ImmutableSet.Builder<HasAndroidResourceDeps> androidResources = ImmutableSet.builder();

    // This visitor finds all AndroidResourceRules that are reachable from the specified rules via
    // rules with types in the TRAVERSABLE_TYPES collection.
    AbstractBreadthFirstTraversal<BuildRule> visitor =
        new AbstractBreadthFirstTraversal<BuildRule>(rules) {

          @Override
          public ImmutableSet<BuildRule> visit(BuildRule rule) {
            HasAndroidResourceDeps androidResourceRule = null;
            if (rule instanceof HasAndroidResourceDeps) {
              androidResourceRule = (HasAndroidResourceDeps) rule;
            }
            if (androidResourceRule != null && androidResourceRule.getRes() != null) {
              androidResources.add(androidResourceRule);
            }

            // Only certain types of rules should be considered as part of this traversal.
            ImmutableSet<BuildRule> depsToVisit = BuildRuleDependencyVisitors.maybeVisitAllDeps(
                rule,
                TRAVERSABLE_TYPES.contains(rule.getClass()));
            if (callback.isPresent()) {
              callback.get().onRuleVisited(rule, depsToVisit);
            }
            return depsToVisit;
          }

        };
    visitor.start();

    return new UnsortedAndroidResourceDeps(androidResources.build());
  }
}

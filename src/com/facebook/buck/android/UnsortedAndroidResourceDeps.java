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

import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.rules.AbstractDependencyVisitor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;

public class UnsortedAndroidResourceDeps {

  private static final ImmutableSet<BuildRuleType> TRAVERSABLE_TYPES = ImmutableSet.of(
      AndroidBinaryDescription.TYPE,
      AndroidInstrumentationApkDescription.TYPE,
      AndroidLibraryDescription.TYPE,
      AndroidResourceDescription.TYPE,
      ApkGenruleDescription.TYPE,
      JavaLibraryDescription.TYPE,
      JavaTestDescription.TYPE,
      RobolectricTestDescription.TYPE);

  public interface Callback {
    public void onRuleVisited(BuildRule rule, ImmutableSet<BuildRule> depsToVisit);
  }

  private final ImmutableSet<HasAndroidResourceDeps> resourceDeps;
  private final ImmutableSet<HasAndroidResourceDeps> assetOnlyDeps;

  public UnsortedAndroidResourceDeps(
      ImmutableSet<HasAndroidResourceDeps> resourceDeps,
      ImmutableSet<HasAndroidResourceDeps> assetOnlyDeps) {
    this.resourceDeps = Preconditions.checkNotNull(resourceDeps);
    this.assetOnlyDeps = Preconditions.checkNotNull(assetOnlyDeps);
  }

  public ImmutableSet<HasAndroidResourceDeps> getResourceDeps() {
    return resourceDeps;
  }

  public ImmutableSet<HasAndroidResourceDeps> getAssetOnlyDeps() {
    return assetOnlyDeps;
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
    final ImmutableSet.Builder<HasAndroidResourceDeps> assetOnlyResources = ImmutableSet.builder();

    // This visitor finds all AndroidResourceRules that are reachable from the specified rules via
    // rules with types in the TRAVERSABLE_TYPES collection.
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(rules) {

      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        HasAndroidResourceDeps androidResourceRule = null;
        if (rule.getBuildable() instanceof HasAndroidResourceDeps) {
          androidResourceRule = (HasAndroidResourceDeps) rule.getBuildable();
        }
        if (androidResourceRule != null) {
          if (androidResourceRule.getRes() != null) {
            androidResources.add(androidResourceRule);
          } else if (androidResourceRule.getAssets() != null) {
            assetOnlyResources.add(androidResourceRule);
          }
        }

        // Only certain types of rules should be considered as part of this traversal.
        BuildRuleType type = rule.getType();
        ImmutableSet<BuildRule> depsToVisit = maybeVisitAllDeps(rule,
            TRAVERSABLE_TYPES.contains(type));
        if (callback.isPresent()) {
          callback.get().onRuleVisited(rule, depsToVisit);
        }
        return depsToVisit;
      }

    };
    visitor.start();

    return new UnsortedAndroidResourceDeps(androidResources.build(), assetOnlyResources.build());
  }
}

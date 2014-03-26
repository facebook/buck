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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Set;

public class KeystoreBuilder {

  private KeystoreBuilder() {
    // Utility class
  }

  public static Builder createBuilder(BuildTarget target) {
    return new Builder(target);
  }

  public static class Builder {

    private final BuildTarget target;
    private final Set<BuildRule> deps;
    private Path store;
    private Path properties;

    public Builder(BuildTarget target) {
      this.target = target;
      this.deps = Sets.newHashSet();
    }

    public Builder setStore(Path store) {
      this.store = store;
      return this;
    }

    public Builder setProperties(Path properties) {
      this.properties = properties;
      return this;
    }

    public Builder addDep(BuildRule dep) {
      deps.add(dep);
      return this;
    }

    public Keystore build() {
      return new Keystore(target, store, properties);
    }

    public BuildRule build(BuildRuleResolver resolver) {
      AbstractBuildable.AnonymousBuildRule rule = new AbstractBuildable.AnonymousBuildRule(
          KeystoreDescription.TYPE,
          build(),
          new FakeBuildRuleParams(target, ImmutableSortedSet.copyOf(deps)));

      resolver.addToIndex(target, rule);

      return rule;
    }
  }
}

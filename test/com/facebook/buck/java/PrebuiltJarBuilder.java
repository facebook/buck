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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PrebuiltJarBuilder {

  private PrebuiltJarBuilder() {
    // Utility class
  }

  public static Builder createBuilder(BuildTarget target) {
    return new Builder(target);
  }

  public static class Builder {

    private Path binaryJar;
    private Optional<String> javadocUrl = Optional.absent();
    private Optional<Path> sourceJar = Optional.absent();
    private final BuildTarget target;
    private final ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

    public Builder(BuildTarget target) {
      this.target = target;
    }

    public Builder setBinaryJar(Path binaryJar) {
      this.binaryJar = binaryJar;
      return this;
    }

    public Builder addDep(BuildRule dep) {
      deps.add(dep);
      return this;
    }

    public PrebuiltJar build() {
      FakeBuildRuleParams params = new FakeBuildRuleParams(target, deps.build());
      return new PrebuiltJar(params, binaryJar, sourceJar, javadocUrl);
    }

    public BuildRule build(BuildRuleResolver resolver) {
      BuildRule rule = new AbstractBuildable.AnonymousBuildRule(
          PrebuiltJarDescription.TYPE,
          build(),
          new FakeBuildRuleParams(target, deps.build()));
      resolver.addToIndex(target, rule);
      return rule;
    }
  }
}

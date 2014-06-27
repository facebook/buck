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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.nio.file.Path;
import java.util.List;

public class JavaTestBuilder {
  private JavaTestBuilder() {
    // Utility class
  }

  public static Builder createBuilder(BuildTarget target) {
    return new Builder(target);
  }

  public static class Builder {

    private final BuildTarget target;
    private final ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    private final ImmutableSortedSet.Builder<SourcePath> srcs = ImmutableSortedSet.naturalOrder();
    private final ImmutableSortedSet.Builder<SourcePath> resources =
        ImmutableSortedSet.naturalOrder();
    private final ImmutableSet.Builder<Label> labels = ImmutableSet.builder();
    private final ImmutableSet.Builder<String> contacts = ImmutableSet.builder();
    private Optional<Path> proguardConfig = Optional.absent();
    private ImmutableSet.Builder<BuildRule> sourcesUnderTest = ImmutableSet.builder();
    private List<String> vmArgs = Lists.newArrayList();

    public Builder(BuildTarget target) {
      this.target = target;
    }

    public Builder addDep(BuildRule rule) {
      deps.add(rule);
      return this;
    }

    public Builder addSrc(Path path) {
      srcs.add(new PathSourcePath(path));
      return this;
    }

    public Builder setSourceUnderTest(ImmutableSet<BuildRule> targets) {
      sourcesUnderTest.addAll(targets);
      return this;
    }

    public Builder setVmArgs(List<String> vmArgs) {
      this.vmArgs = vmArgs;
      return this;
    }

    public JavaTest build() {
      return build(new BuildRuleResolver());
    }

    public JavaTest build(BuildRuleResolver resolver) {
      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
          .setType(JavaTestDescription.TYPE)
          .setDeps(deps.build())
          .build();
      JavaTest test = new JavaTest(
          params,
          srcs.build(),
          resources.build(),
          labels.build(),
          contacts.build(),
          proguardConfig,
          /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
          JavacOptions.DEFAULTS,
          vmArgs,
          sourcesUnderTest.build(),
          Optional.<Path>absent());
      resolver.addToIndex(test);
      return test;
    }
  }
}

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
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.google.common.collect.ImmutableSortedSet;

public class JavaBinaryRuleBuilder {

  private JavaBinaryRuleBuilder() {
    // Utility class
  }

  public static Builder newBuilder(BuildTarget buildTarget) {
    return new Builder(buildTarget);
  }

  public static class Builder {
    private BuildTarget buildTarget;
    private ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.of();
    private String mainClass;

    public Builder(BuildTarget buildTarget) {
      this.buildTarget = buildTarget;
    }

    public Builder setDeps(ImmutableSortedSet<BuildRule> deps) {
      this.deps = deps;
      return this;
    }

    public Builder setMainClass(String mainClass) {
      this.mainClass = mainClass;
      return this;
    }

    public JavaBinary buildAsBuildable() {
      return new JavaBinary(
          buildTarget,
          deps,
          mainClass,
          /* manifestFile */ null,
          /* metaInfDirectory */ null,
          new DefaultDirectoryTraverser());
    }

    public BuildRule build() {
      return new AbstractBuildable.AnonymousBuildRule(
          JavaBinaryDescription.TYPE,
          buildAsBuildable(),
          new FakeBuildRuleParams(buildTarget));
    }
  }
}


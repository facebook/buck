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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class GenruleBuilder {

  private GenruleBuilder() {
    // Utility class
  }

  public static Builder createGenrule(BuildTarget target) {
    return new Builder(target);
  }

  public static class Builder {
    private final GenruleDescription description;
    private final GenruleDescription.Arg args;
    private final BuildTarget target;
    private Function<Path, Path> absolutifier;
    private final Set<BuildRule> deps = Sets.newHashSet();
    private final ImmutableList.Builder<Path> srcs = ImmutableList.builder();

    Builder(BuildTarget target) {
      this.target = target;
      this.description = new GenruleDescription();
      this.args = this.description.createUnpopulatedConstructorArg();

      // Populate the args with sensible defaults.
      this.args.bash = Optional.absent();
      this.args.cmd = Optional.absent();
      this.args.cmdExe = Optional.absent();
    }

    public Genrule build() {
      final ImmutableSortedSet<BuildRule> depRules = ImmutableSortedSet.copyOf(deps);
      args.deps = Optional.of(depRules);
      args.srcs = Optional.of(srcs.build());

      BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
          .setDeps(depRules)
          .setType(GenruleDescription.TYPE)
          .setProjectFilesystem(
              new ProjectFilesystem(Paths.get(".")) {
                @Override
                public Function<Path, Path> getAbsolutifier() {
                  return Optional.fromNullable(absolutifier)
                      .or(IdentityPathAbsolutifier.getIdentityAbsolutifier());
                }
              })
          .build();
      return description.createBuildRule(params, new BuildRuleResolver(), args);
    }

    public Builder setRelativeToAbsolutePathFunctionForTesting(Function<Path, Path> absolutifier) {
      this.absolutifier = absolutifier;
      return this;
    }

    public Builder setBash(String bash) {
      args.bash = Optional.of(bash);
      return this;
    }

    public Builder setCmd(String cmd) {
      args.cmd = Optional.of(cmd);
      return this;
    }

    public Builder setCmdExe(String cmdExe) {
      args.cmdExe = Optional.of(cmdExe);
      return this;
    }

    public Builder setOut(String out) {
      args.out = Preconditions.checkNotNull(out);
      return this;
    }

    public Builder addDep(BuildRule rule) {
      deps.add(rule);
      return this;
    }

    public Builder addSrc(Path path) {
      srcs.add(path);
      return this;
    }
  }
}

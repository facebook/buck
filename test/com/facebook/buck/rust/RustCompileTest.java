/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rust;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;

public class RustCompileTest {
  @Test(expected = HumanReadableException.class)
  public void noCrateRootInSrcs() {
    RustCompile linkable = new FakeRustCompile("//:donotcare", ImmutableSortedSet.of());
    linkable.getCrateRoot();
  }

  @Test
  public void crateRootMainInSrcs() {
    RustCompile linkable = new FakeRustCompile(
        "//:donotcare",
        ImmutableSortedSet.of(new FakeSourcePath("main.rs")));
    assertThat(linkable.getCrateRoot().toString(), Matchers.endsWith("main.rs"));
  }

  @Test
  public void crateRootTargetNameInSrcs() {
    RustCompile linkable = new FakeRustCompile(
        "//:myname",
        ImmutableSortedSet.of(new FakeSourcePath("myname.rs")));
    assertThat(linkable.getCrateRoot().toString(), Matchers.endsWith("myname.rs"));
  }

  @Test(expected = HumanReadableException.class)
  public void crateRootMainAndTargetNameInSrcs() {
    RustCompile linkable = new FakeRustCompile(
        "//:myname",
        ImmutableSortedSet.of(
            new FakeSourcePath("main.rs"),
            new FakeSourcePath("myname.rs")));
    linkable.getCrateRoot();
  }

  class FakeRustCompile extends RustCompile {
    FakeRustCompile(
        String target,
        ImmutableSortedSet<SourcePath> srcs) {
      super(
          new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target)).build(),
          new SourcePathResolver(
              new BuildRuleResolver(
                  TargetGraph.EMPTY,
                  new DefaultTargetNodeToBuildRuleTransformer())),
          "myname",
          srcs,
          /* flags */ ImmutableList.of(),
          /* features */ ImmutableSortedSet.of(),
          /* nativePaths */ ImmutableSortedSet.of(),
          Paths.get("somewhere"),
          new Tool() {
            @Override
            public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
              return ImmutableSortedSet.of();
            }

            @Override
            public ImmutableCollection<SourcePath> getInputs() {
              return ImmutableSortedSet.of();
            }

            @Override
            public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
              return ImmutableList.of();
            }

            @Override
            public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
              return ImmutableMap.of();
            }

            @Override
            public void appendToRuleKey(RuleKeyObjectSink sink) {
              // Do nothing.
            }
          },
          Linker.LinkableDepType.STATIC);
    }

    @Override
    protected String getDefaultSource() {
      return "main.rs";
    }
  }
}

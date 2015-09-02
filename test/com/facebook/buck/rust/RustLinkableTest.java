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

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;

public class RustLinkableTest {
  @Test(expected = HumanReadableException.class)
  public void noCrateRootInSrcs() {
    RustLinkable linkable = new FakeRustLinkable("//:donotcare", ImmutableSet.<SourcePath>of());
    linkable.getCrateRoot();
  }

  @Test
  public void crateRootMainInSrcs() {
    RustLinkable linkable = new FakeRustLinkable(
        "//:donotcare",
        ImmutableSet.<SourcePath>of(new TestSourcePath("main.rs")));
    assertThat(linkable.getCrateRoot().toString(), Matchers.endsWith("main.rs"));
  }

  @Test
  public void crateRootTargetNameInSrcs() {
    RustLinkable linkable = new FakeRustLinkable(
        "//:myname",
        ImmutableSet.<SourcePath>of(new TestSourcePath("myname.rs")));
    assertThat(linkable.getCrateRoot().toString(), Matchers.endsWith("myname.rs"));
  }

  @Test(expected = HumanReadableException.class)
  public void crateRootMainAndTargetNameInSrcs() {
    RustLinkable linkable = new FakeRustLinkable(
        "//:myname",
        ImmutableSet.<SourcePath>of(
            new TestSourcePath("main.rs"),
            new TestSourcePath("myname.rs")));
    linkable.getCrateRoot();
  }

  class FakeRustLinkable extends RustLinkable {
    FakeRustLinkable(
        String target,
        ImmutableSet<SourcePath> srcs) {
      super(
          new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target)).build(),
          new SourcePathResolver(new BuildRuleResolver()),
          srcs,
          /* flags */ ImmutableList.<String>of(),
          /* features */ ImmutableSet.<String>of(),
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
            public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
              return builder;
            }
          });
    }
  }
}

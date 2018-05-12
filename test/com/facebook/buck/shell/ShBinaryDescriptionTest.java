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

package com.facebook.buck.shell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.FakeSourcePath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ShBinaryDescriptionTest {

  @Test
  public void mainIsIncludedInCommand() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    PathSourcePath main = FakeSourcePath.of("main.sh");
    ShBinary shBinary =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMain(main)
            .build(resolver);
    assertThat(
        BuildableSupport.deriveInputs(shBinary.getExecutableCommand())
            .collect(ImmutableList.toImmutableList()),
        Matchers.hasItem(main));
  }

  @Test
  public void resourcesAreIncludedInCommand() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    PathSourcePath main = FakeSourcePath.of("main.sh");
    PathSourcePath resource = FakeSourcePath.of("resource.dat");
    ShBinary shBinary =
        new ShBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMain(main)
            .setResources(ImmutableSet.of(resource))
            .build(resolver);
    assertThat(
        BuildableSupport.deriveInputs(shBinary.getExecutableCommand())
            .collect(ImmutableList.toImmutableList()),
        Matchers.hasItem(resource));
  }
}

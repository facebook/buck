/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HaskellBinaryDescriptionTest {

  @Test
  public void compilerFlags() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-compiler-flag";
    HaskellBinaryBuilder builder =
        new HaskellBinaryBuilder(target).setCompilerFlags(ImmutableList.of(flag));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    builder.build(resolver);
    BuildTarget compileTarget =
        HaskellDescriptionUtils.getCompileBuildTarget(
            target, CxxPlatformUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC);
    HaskellCompileRule rule = resolver.getRuleWithType(compileTarget, HaskellCompileRule.class);
    assertThat(rule.getFlags(), Matchers.hasItem(flag));
  }

  @Test
  public void depQuery() throws Exception {
    HaskellLibraryBuilder transitiveDepBuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"));
    HaskellLibraryBuilder depBuilder =
        new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setDeps(ImmutableSortedSet.of(transitiveDepBuilder.getTarget()));
    HaskellBinaryBuilder builder =
        new HaskellBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setDepQuery(Query.of("filter(transitive, deps(//:dep))"));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveDepBuilder.build(), depBuilder.build(), builder.build());
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    HaskellLibrary transitiveDep = transitiveDepBuilder.build(resolver, targetGraph);
    HaskellLibrary dep = depBuilder.build(resolver, targetGraph);
    HaskellBinary binary = (HaskellBinary) builder.build(resolver, targetGraph);
    assertThat(binary.getBinaryDeps(), Matchers.hasItem(transitiveDep));
    assertThat(binary.getBinaryDeps(), Matchers.not(Matchers.hasItem(dep)));
  }
}

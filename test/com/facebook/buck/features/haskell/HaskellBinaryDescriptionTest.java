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

package com.facebook.buck.features.haskell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.query.Query;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HaskellBinaryDescriptionTest {

  @Test
  public void compilerFlags() {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-compiler-flag";
    HaskellBinaryBuilder builder =
        new HaskellBinaryBuilder(target).setCompilerFlags(ImmutableList.of(flag));
    ActionGraphBuilder graphBuilder =
        new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    builder.build(graphBuilder);
    BuildTarget compileTarget =
        HaskellDescriptionUtils.getCompileBuildTarget(
            target, HaskellTestUtils.DEFAULT_PLATFORM, Linker.LinkableDepType.STATIC, false);
    HaskellCompileRule rule = graphBuilder.getRuleWithType(compileTarget, HaskellCompileRule.class);
    assertThat(rule.getFlags(), Matchers.hasItem(flag));
  }

  @Test
  public void depQuery() {
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
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    HaskellLibrary transitiveDep = transitiveDepBuilder.build(graphBuilder, targetGraph);
    HaskellLibrary dep = depBuilder.build(graphBuilder, targetGraph);
    HaskellBinary binary = (HaskellBinary) builder.build(graphBuilder, targetGraph);
    assertThat(binary.getBinaryDeps(), Matchers.hasItem(transitiveDep));
    assertThat(binary.getBinaryDeps(), Matchers.not(Matchers.hasItem(dep)));
  }
}

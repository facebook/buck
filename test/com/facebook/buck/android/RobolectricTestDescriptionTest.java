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

package com.facebook.buck.android;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeExportDependenciesRule;
import com.facebook.buck.rules.FakeTargetNodeBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;

import org.hamcrest.Matchers;
import org.junit.Test;

public class RobolectricTestDescriptionTest {

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() throws Exception {
    SourcePathResolver emptyPathResolver =
        new SourcePathResolver(
            new BuildRuleResolver(
                TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer()));

    FakeBuildRule exportedRule =
        new FakeBuildRule("//:exported_rule", emptyPathResolver);
    FakeExportDependenciesRule exportingRule =
        new FakeExportDependenciesRule("//:exporting_rule", emptyPathResolver, exportedRule);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNode<?, ?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(target)
            .addDep(exportingRule.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        robolectricTestNode,
        FakeTargetNodeBuilder.build(exportedRule),
        FakeTargetNodeBuilder.build(exportingRule));
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    RobolectricTest robolectricTest =
        (RobolectricTest) resolver.requireRule(robolectricTestNode.getBuildTarget());

    assertThat(robolectricTest.getCompiledTestsLibrary().getDeps(),
        Matchers.<BuildRule>hasItem(exportedRule));
  }

  @Test
  public void rulesExportedFromProvidedDepsBecomeFirstOrderDeps() throws Exception {
    SourcePathResolver emptyPathResolver =
        new SourcePathResolver(
            new BuildRuleResolver(
                TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer()));

    FakeBuildRule exportedRule =
        new FakeBuildRule("//:exported_rule", emptyPathResolver);
    FakeExportDependenciesRule exportingRule =
            new FakeExportDependenciesRule("//:exporting_rule", emptyPathResolver, exportedRule);

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    TargetNode<?, ?> robolectricTestNode =
        RobolectricTestBuilder.createBuilder(target)
            .addProvidedDep(exportingRule.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(
        robolectricTestNode,
        FakeTargetNodeBuilder.build(exportedRule),
        FakeTargetNodeBuilder.build(exportingRule));
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    RobolectricTest robolectricTest =
        (RobolectricTest) resolver.requireRule(robolectricTestNode.getBuildTarget());

    assertThat(robolectricTest.getCompiledTestsLibrary().getDeps(),
        Matchers.<BuildRule>hasItem(exportedRule));
  }

}

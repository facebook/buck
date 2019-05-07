/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.build.action.resolver;

import static org.junit.Assert.assertSame;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import org.junit.Test;

public class BuildTargetToBuildEngineActionResolverTest {

  @Test
  public void resolvesBuildEngineActionFromActionGraphResolver() {
    BuildTarget target = BuildTargetFactory.newInstance("//my:foo");
    BuildRule rule = new FakeBuildRule(target);
    ActionGraphBuilder actionGraphBuilder =
        new TestActionGraphBuilder(
            TargetGraphFactory.newInstance(FakeTargetNodeBuilder.newBuilder(target).build()),
            new TargetNodeToBuildRuleTransformer() {
              @Override
              public <T> BuildRule transform(
                  ToolchainProvider toolchainProvider,
                  TargetGraph targetGraph,
                  ActionGraphBuilder graphBuilder,
                  TargetNode<T> targetNode) {
                return rule;
              }
            });

    actionGraphBuilder.requireRule(target); // build the rule first
    BuildTargetToBuildEngineActionResolver resolver =
        new BuildTargetToBuildEngineActionResolver(actionGraphBuilder);
    assertSame(rule, resolver.resolve(target));
  }
}

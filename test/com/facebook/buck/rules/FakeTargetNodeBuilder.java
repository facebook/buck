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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

public class FakeTargetNodeBuilder
    extends AbstractNodeBuilder<
        FakeTargetNodeArg.Builder, FakeTargetNodeArg, FakeTargetNodeBuilder.FakeDescription,
        BuildRule> {

  private FakeTargetNodeBuilder(FakeDescription description, BuildTarget target) {
    super(description, target);
  }

  public static FakeTargetNodeBuilder newBuilder(BuildRule rule) {
    return new FakeTargetNodeBuilder(new FakeDescription(rule), rule.getBuildTarget());
  }

  public static TargetNode<FakeTargetNodeArg, FakeDescription> build(BuildRule rule) {
    return newBuilder(rule).build();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractFakeTargetNodeArg extends CommonDescriptionArg {}

  public static class FakeDescription implements Description<FakeTargetNodeArg> {
    private final BuildRule rule;

    public FakeDescription(BuildRule rule) {
      this.rule = rule;
    }

    @Override
    public Class<FakeTargetNodeArg> getConstructorArgType() {
      return FakeTargetNodeArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        TargetGraph targetGraph,
        BuildRuleParams params,
        BuildRuleResolver resolver,
        CellPathResolver cellRoots,
        FakeTargetNodeArg args)
        throws NoSuchBuildTargetException {
      return rule;
    }
  }
}

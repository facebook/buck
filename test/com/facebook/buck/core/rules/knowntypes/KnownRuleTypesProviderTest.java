/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.knowntypes;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class KnownRuleTypesProviderTest {
  private final FakeTargetNodeBuilder.FakeDescription fakeDescription =
      new FakeTargetNodeBuilder.FakeDescription();

  class TestFactory implements KnownNativeRuleTypesFactory {
    @Override
    public KnownNativeRuleTypes create(Cell cell) {
      return KnownNativeRuleTypes.of(
          ImmutableList.of(fakeDescription), ImmutableList.of(), ImmutableList.of());
    }
  }

  Cell createCell(boolean enableUserDefinedRules) {
    BuckConfig config =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "parser",
                    ImmutableMap.of(
                        "user_defined_rules", enableUserDefinedRules ? "enabled" : "disabled")))
            .build();
    return new TestCellBuilder().setBuckConfig(config).build();
  }

  @Test
  public void returnsKnownNativeRuleTypesIfUserDefinedRulesDisabled() {
    KnownRuleTypesProvider provider = new KnownRuleTypesProvider(new TestFactory());
    Cell cell = createCell(false);

    KnownRuleTypes knownRuleTypes = provider.get(cell);
    KnownNativeRuleTypes knownNativeRuleTypes = provider.getNativeRuleTypes(cell);

    assertSame(knownRuleTypes, knownNativeRuleTypes);
  }

  @Test
  public void returnsHybridKnownRuleTypesIfUserDefinedRulesEnabled() {
    KnownRuleTypesProvider provider = new KnownRuleTypesProvider(new TestFactory());
    Cell cell = createCell(true);

    KnownRuleTypes knownRuleTypes = provider.get(cell);
    KnownNativeRuleTypes knownNativeRuleTypes = provider.getNativeRuleTypes(cell);

    assertNotNull(knownRuleTypes.getRuleType("fake"));
    assertTrue(knownRuleTypes instanceof HybridKnownRuleTypes);
    assertSame(knownRuleTypes.getRuleType("fake"), knownNativeRuleTypes.getRuleType("fake"));
  }
}

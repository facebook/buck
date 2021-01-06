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

package com.facebook.buck.core.rules.knowntypes.provider;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.rules.knowntypes.HybridKnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.EvalException;
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
                    "rule_analysis",
                    ImmutableMap.of("mode", "PROVIDER_COMPATIBLE"),
                    "parser",
                    ImmutableMap.of(
                        "default_build_file_syntax",
                        "SKYLARK",
                        "user_defined_rules",
                        enableUserDefinedRules ? "enabled" : "disabled")))
            .build();
    return new TestCellBuilder().setBuckConfig(config).build().getRootCell();
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
  public void returnsHybridKnownRuleTypesIfUserDefinedRulesEnabled()
      throws LabelSyntaxException, EvalException {
    KnownRuleTypesProvider provider = new KnownRuleTypesProvider(new TestFactory());
    Cell cell = createCell(true);
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();

    KnownRuleTypes knownRuleTypes = provider.get(cell);
    KnownNativeRuleTypes knownNativeRuleTypes = provider.getNativeRuleTypes(cell);
    KnownUserDefinedRuleTypes knownUserDefinedRuleTypes = provider.getUserDefinedRuleTypes(cell);
    knownUserDefinedRuleTypes.addRule(rule);

    assertNotNull(knownRuleTypes.getDescriptorByName("fake").getRuleType());
    assertTrue(knownRuleTypes instanceof HybridKnownRuleTypes);
    assertSame(
        knownRuleTypes.getDescriptorByName("fake").getRuleType(),
        knownNativeRuleTypes.getDescriptorByName("fake").getRuleType());

    assertNotNull(knownRuleTypes.getDescriptorByName(rule.getName()).getRuleType());
    assertSame(
        knownRuleTypes.getDescriptorByName(rule.getName()).getRuleType(),
        knownUserDefinedRuleTypes.getDescriptorByName(rule.getName()).getRuleType());
  }
}

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
package com.facebook.buck.core.starlark.knowntypes;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.EvalException;
import org.junit.Test;

public class KnownUserDefinedRuleTypesTest {

  @Test
  public void setsValueProperly() throws LabelSyntaxException, EvalException {
    KnownUserDefinedRuleTypes knownRules = new KnownUserDefinedRuleTypes();
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();

    knownRules.addRule(rule);

    assertSame(rule, knownRules.getRule(rule.getName()));
  }

  @Test
  public void returnsNullIfFileNotUsed() throws LabelSyntaxException, EvalException {
    KnownUserDefinedRuleTypes knownRules = new KnownUserDefinedRuleTypes();
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();

    assertNull(knownRules.getRule(rule.getName()));
  }

  @Test
  public void returnsNullIfFileUsedButNameIsNot() throws LabelSyntaxException, EvalException {
    KnownUserDefinedRuleTypes knownRules = new KnownUserDefinedRuleTypes();
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();

    knownRules.addRule(rule);

    assertNull(knownRules.getRule(rule.getName().replace(rule.getExportedName(), "blargl")));
    assertSame(rule, knownRules.getRule(rule.getName()));
  }

  @Test
  public void rulesAreMissingAfterInvalidatingOwningPath()
      throws LabelSyntaxException, EvalException {
    KnownUserDefinedRuleTypes knownRules = new KnownUserDefinedRuleTypes();
    SkylarkUserDefinedRule rule = FakeSkylarkUserDefinedRuleFactory.createSimpleRule();

    knownRules.addRule(rule);

    assertSame(rule, knownRules.getRule(rule.getName()));
    knownRules.invalidateExtension(rule.getLabel());
    assertNull(knownRules.getRule(rule.getName()));
  }

  @Test
  public void failsGetIfIdentifierEndsWithColon() {
    KnownUserDefinedRuleTypes knownRules = new KnownUserDefinedRuleTypes();

    assertNull(knownRules.getRule("//foo:bar.bzl:"));
  }

  @Test
  public void failsGetIfIdentifierHasNoColon() {
    KnownUserDefinedRuleTypes knownRules = new KnownUserDefinedRuleTypes();

    assertNull(knownRules.getRule("//foo"));
  }
}

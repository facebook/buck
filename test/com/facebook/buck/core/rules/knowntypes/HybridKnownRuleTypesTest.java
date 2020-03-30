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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.starlark.coercer.SkylarkDescriptionArgBuilder;
import com.facebook.buck.core.starlark.knowntypes.KnownUserDefinedRuleTypes;
import com.facebook.buck.core.starlark.rule.SkylarkDescription;
import com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.starlark.rule.attr.impl.StringAttribute;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.skylark.function.FakeSkylarkUserDefinedRuleFactory;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.EvalException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class HybridKnownRuleTypesTest {

  private KnownNativeRuleTypes nativeRuleTypes;
  private KnownUserDefinedRuleTypes userDefinedRuleTypes;

  @Rule public ExpectedException expected = ExpectedException.none();

  @Before
  public void setUp() throws LabelSyntaxException, EvalException {
    KnownRuleTestDescription description = new KnownRuleTestDescription("FooBar");
    nativeRuleTypes =
        KnownNativeRuleTypes.of(
            ImmutableList.of(description), ImmutableList.of(), ImmutableList.of());
    userDefinedRuleTypes = new KnownUserDefinedRuleTypes();
    StringAttribute attr = StringAttribute.of("default", "", false, ImmutableList.of());
    SkylarkUserDefinedRule rule =
        FakeSkylarkUserDefinedRuleFactory.createSingleArgRuleWithLabel(
            "baz_rule", "baz", attr, "//foo:bar.bzl");
    SkylarkUserDefinedRule rule2 =
        FakeSkylarkUserDefinedRuleFactory.createSingleArgRuleWithLabel(
            "other_baz_rule", "baz", attr, "@repo//foo:bar.bzl");
    userDefinedRuleTypes.addRule(rule);
    userDefinedRuleTypes.addRule(rule2);
  }

  @Test
  public void returnsCorrectRuleType() {
    KnownRuleTypes knownTypes = new HybridKnownRuleTypes(nativeRuleTypes, userDefinedRuleTypes);

    assertEquals(
        RuleType.of("known_rule_test", RuleType.Kind.BUILD),
        knownTypes.getDescriptorByName("known_rule_test").getRuleType());
    assertEquals(
        RuleType.of("//foo:bar.bzl:baz_rule", RuleType.Kind.BUILD),
        knownTypes.getDescriptorByName("//foo:bar.bzl:baz_rule").getRuleType());
    assertEquals(
        RuleType.of("@repo//foo:bar.bzl:other_baz_rule", RuleType.Kind.BUILD),
        knownTypes.getDescriptorByName("@repo//foo:bar.bzl:other_baz_rule").getRuleType());
  }

  @Test
  public void errorsIfNoRuleWithIdentifierExists() {
    KnownRuleTypes knownTypes = new HybridKnownRuleTypes(nativeRuleTypes, userDefinedRuleTypes);

    expected.expect(RuntimeException.class);

    knownTypes.getDescriptorByName("//foo:bar.bzl:invalid_rule").getRuleType();
  }

  @Test
  public void returnsCorrectDescription() {
    KnownRuleTypes knownTypes = new HybridKnownRuleTypes(nativeRuleTypes, userDefinedRuleTypes);

    BaseDescription<?> foundDescription =
        knownTypes.getDescriptorByName("known_rule_test").getDescription();
    assertEquals(KnownRuleTestDescription.class, foundDescription.getClass());

    assertEquals(
        SkylarkDescription.class,
        knownTypes.getDescriptorByName("//foo:bar.bzl:baz_rule").getDescription().getClass());
  }

  @Test
  public void returnsSkylarkDescriptionArgBuilderForUserDefinedRule() {
    KnownRuleTypes knownTypes = new HybridKnownRuleTypes(nativeRuleTypes, userDefinedRuleTypes);
    RuleType ruleType = knownTypes.getDescriptorByName("//foo:bar.bzl:baz_rule").getRuleType();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    DefaultTypeCoercerFactory factory = new DefaultTypeCoercerFactory();

    DataTransferObjectDescriptor<SkylarkDescriptionArg> argDescriptor =
        knownTypes
            .getDescriptorByNameChecked(ruleType.getName(), SkylarkDescriptionArg.class)
            .dataTransferObjectDescriptor(factory);
    SkylarkDescriptionArgBuilder builder =
        (SkylarkDescriptionArgBuilder) argDescriptor.getBuilderFactory().get();
    builder.setPostCoercionValue("baz", "value");
    builder.setPostCoercionValue("name", "thename");

    // Ensure that we can cast back properly
    SkylarkDescriptionArg built = argDescriptor.build(builder, target);

    assertEquals("value", built.getPostCoercionValue("baz"));
  }

  @Test
  public void returnsImmutableDescriptionArgBuilderForNativeRule() {
    KnownRuleTypes knownTypes = new HybridKnownRuleTypes(nativeRuleTypes, userDefinedRuleTypes);
    RuleType ruleType = knownTypes.getDescriptorByName("known_rule_test").getRuleType();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    DefaultTypeCoercerFactory factory = new DefaultTypeCoercerFactory();

    DataTransferObjectDescriptor<KnownRuleTestDescriptionArg> argDescriptor =
        knownTypes
            .getDescriptorByNameChecked(ruleType.getName(), KnownRuleTestDescriptionArg.class)
            .dataTransferObjectDescriptor(factory);

    KnownRuleTestDescriptionArg.Builder builder =
        (KnownRuleTestDescriptionArg.Builder) argDescriptor.getBuilderFactory().get();
    builder.setName("that_rule");
    KnownRuleTestDescriptionArg ret = argDescriptor.build(builder, target);
    assertEquals("that_rule", ret.getName());
  }
}

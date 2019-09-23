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
package com.facebook.buck.core.starlark.rule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.rules.coercer.BuildTargetTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.UnconfiguredBuildTargetTypeCoercer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import org.junit.Test;

public class SkylarkRuleContextAttrTest {

  static class TestAttribute extends Attribute<BuildTarget> {

    @Override
    public Object getPreCoercionDefaultValue() {
      return "//foo:bar";
    }

    @Override
    public String getDoc() {
      return "";
    }

    @Override
    public boolean getMandatory() {
      return false;
    }

    @Override
    public TypeCoercer<BuildTarget> getTypeCoercer() {
      return new BuildTargetTypeCoercer(
          new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory()));
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      printer.append("<test_attr>");
    }

    @Override
    public PostCoercionTransform<ImmutableMap<BuildTarget, ProviderInfoCollection>, BuildTarget>
        getPostCoercionTransform() {
      return (coercedValue, deps) -> BuildTargetFactory.newInstance((String) coercedValue);
    }
  }

  @Test
  public void getsValue() {
    SkylarkRuleContextAttr attr =
        new SkylarkRuleContextAttr(
            "some_method",
            ImmutableMap.of("foo", "foo_value"),
            ImmutableMap.of(),
            ImmutableMap.of());

    assertEquals("foo_value", attr.getValue("foo"));
    assertNull(attr.getValue("bar"));
  }

  @Test
  public void returnsAllFieldsInSortedOrder() {
    SkylarkRuleContextAttr attr =
        new SkylarkRuleContextAttr(
            "some_method",
            ImmutableMap.of("foo", "foo_value", "bar", "bar_value"),
            ImmutableMap.of(),
            ImmutableMap.of());

    assertEquals(ImmutableSet.of("bar", "foo"), attr.getFieldNames());
  }

  @Test
  public void performsPostCoercionTransformsOnFieldsIfRequested() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProviderInfoCollection providerInfos = TestProviderInfoCollectionImpl.builder().build();
    TestAttribute attr = new TestAttribute();
    SkylarkRuleContextAttr ctxAttr =
        new SkylarkRuleContextAttr(
            "some_method",
            ImmutableMap.of("foo", "foo_value", "bar", "//foo:bar"),
            ImmutableMap.of("bar", attr),
            ImmutableMap.of(target, providerInfos));

    assertEquals("foo_value", ctxAttr.getValue("foo"));
    assertEquals(target, ctxAttr.getValue("bar"));
  }
}

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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class SkylarkRuleContextAttrTest {

  @Test
  public void getsValue() {
    SkylarkRuleContextAttr attr =
        new SkylarkRuleContextAttr("some_method", ImmutableMap.of("foo", "foo_value"));

    assertEquals("foo_value", attr.getValue("foo"));
    assertNull(attr.getValue("bar"));
  }

  @Test
  public void returnsAllFieldsInSortedOrder() {
    SkylarkRuleContextAttr attr =
        new SkylarkRuleContextAttr(
            "some_method", ImmutableMap.of("foo", "foo_value", "bar", "bar_value"));

    assertEquals(ImmutableSet.of("bar", "foo"), attr.getFieldNames());
  }
}

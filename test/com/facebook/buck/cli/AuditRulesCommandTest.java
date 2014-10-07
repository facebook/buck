/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class AuditRulesCommandTest {

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void testCreateDisplayString() {
    assertEquals("None", AuditRulesCommand.createDisplayString(null));
    assertEquals("True", AuditRulesCommand.createDisplayString(true));
    assertEquals("False", AuditRulesCommand.createDisplayString(false));
    assertEquals("42", AuditRulesCommand.createDisplayString(42));
    assertEquals("3.14", AuditRulesCommand.createDisplayString(3.14));
    assertEquals("\"Hello, world!\"", AuditRulesCommand.createDisplayString("Hello, world!"));
    assertEquals("[\n]", AuditRulesCommand.createDisplayString(ImmutableList.<String>of()));
    assertEquals("[\n  \"foo\",\n  \"bar\",\n  \"baz\",\n]",
        AuditRulesCommand.createDisplayString(ImmutableList.of("foo", "bar", "baz")));
    assertEquals("{\n  \"foo\": 1,\n  \"bar\": 2,\n  \"baz\": 3,\n}",
        AuditRulesCommand.createDisplayString(ImmutableMap.of("foo", 1, "bar", 2, "baz", 3)));
    assertEquals("{\n  \"foo\": [\n    1,\n  ],\n}",
        AuditRulesCommand.createDisplayString(ImmutableMap.of("foo", ImmutableList.of(1))));
  }

  @Test(expected = IllegalStateException.class)
  public void testCreateDisplayStringRejectsUnknownType() {
    AuditRulesCommand.createDisplayString(new Object());
  }
}

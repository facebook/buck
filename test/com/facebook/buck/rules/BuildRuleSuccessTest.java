/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit test for {@link BuildRuleSuccess}.
 */
public class BuildRuleSuccessTest {

  @Test
  public void testBasicFunctionality() {
    BuildRule rule = createMock(BuildRule.class);
    expect(rule.getFullyQualifiedName()).andReturn("//java/com/example/base:base");
    replay(rule);

    BuildRuleSuccess buildRuleSuccess = new BuildRuleSuccess(
        rule, BuildRuleSuccess.Type.FETCHED_FROM_CACHE);
    assertEquals("//java/com/example/base:base", buildRuleSuccess.toString());
    assertEquals(BuildRuleSuccess.Type.FETCHED_FROM_CACHE, buildRuleSuccess.getType());

    verify(rule);
  }
}

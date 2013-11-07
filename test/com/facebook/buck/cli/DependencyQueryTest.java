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

import com.google.common.base.Optional;

import org.easymock.EasyMock;
import org.junit.Test;

public class DependencyQueryTest {

  @Test
  public void testParseQueryStringResolvesBuildTargetAlias() {
    BuckConfig buckConfig = EasyMock.createMock(BuckConfig.class);
    EasyMock.expect(buckConfig.getBuildTargetForAlias("fb4a"))
        .andReturn("//apps/fb4a:fb4a");
    EasyMock.expect(buckConfig.getBuildTargetForAlias("base"))
        .andReturn("//java/com/facebook/base:base");
    EasyMock.replay(buckConfig);

    CommandLineBuildTargetNormalizer commandLineBuildTargetNormalizer =
        new CommandLineBuildTargetNormalizer(buckConfig);
    DependencyQuery query = DependencyQuery.parseQueryString("fb4a -> base",
        commandLineBuildTargetNormalizer);

    assertEquals("//apps/fb4a:fb4a", query.getTarget());
    assertEquals(Optional.of("//java/com/facebook/base:base"), query.getSource());

    EasyMock.verify(buckConfig);
  }
}

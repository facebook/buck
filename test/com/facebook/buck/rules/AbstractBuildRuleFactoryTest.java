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

import static com.facebook.buck.parser.BuildTargetPatternParser.VISIBILITY_PUBLIC;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.SubdirectoryBuildTargetPattern;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.Map;

public class AbstractBuildRuleFactoryTest {

  @Test
  public void testGetVisibilityTargets() throws NoSuchBuildTargetException {
    Map<String, ?> config = ImmutableMap.of(
        "visibility" , ImmutableList.of(VISIBILITY_PUBLIC, "//...", "//com/facebook/..."));
    BuildRuleFactoryParams params = NonCheckingBuildRuleFactoryParams.
        createNonCheckingBuildRuleFactoryParams(
            config,
            null,
            BuildTargetFactory.newInstance("//example:target"));
    assertEquals(
        ImmutableSet.of(
            BuildTargetPattern.MATCH_ALL,
            new SubdirectoryBuildTargetPattern(""),
            new SubdirectoryBuildTargetPattern("com/facebook/")),
        AbstractBuildRuleFactory.getVisibilityPatterns(params));
  }
}

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

package com.facebook.buck.core.rules.analysis.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class RuleAnalysisCacheTest {

  private RuleAnalysisCacheImpl cache;

  @Before
  public void setUp() {
    cache = new RuleAnalysisCacheImpl();
  }

  @Test
  public void emptyCacheReturnsEmpty() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    assertEquals(Optional.empty(), cache.get(RuleAnalysisKey.of(buildTarget)));
  }

  @Test
  public void nonEmptyCacheReturnsEntry() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my:target");

    RuleAnalysisResult cachedResult =
        ImmutableRuleAnalysisResultImpl.of(
            buildTarget, TestProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());
    cache.put(RuleAnalysisKey.of(buildTarget), cachedResult);

    // assert that we cache and return the same instance
    assertSame(cachedResult, cache.get(RuleAnalysisKey.of(buildTarget)).get());

    BuildTarget buildTarget2 = BuildTargetFactory.newInstance("//my:target2");

    assertEquals(Optional.empty(), cache.get(RuleAnalysisKey.of(buildTarget2)));
  }

  @Test
  public void multipleCacheEntriesReturnCorrectEntry() {
    BuildTarget buildTarget1 = BuildTargetFactory.newInstance("//my:target1");
    BuildTarget buildTarget2 = BuildTargetFactory.newInstance("//my:target2");
    BuildTarget buildTarget3 = BuildTargetFactory.newInstance("//my:target3");

    RuleAnalysisResult cachedResult1 =
        ImmutableRuleAnalysisResultImpl.of(
            buildTarget1, TestProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());
    RuleAnalysisResult cachedResult2 =
        ImmutableRuleAnalysisResultImpl.of(
            buildTarget2, TestProviderInfoCollectionImpl.builder().build(), ImmutableMap.of());

    cache.put(RuleAnalysisKey.of(buildTarget1), cachedResult1);
    cache.put(RuleAnalysisKey.of(buildTarget2), cachedResult2);

    assertEquals(Optional.empty(), cache.get(RuleAnalysisKey.of(buildTarget3)));

    assertSame(cachedResult1, cache.get(RuleAnalysisKey.of(buildTarget1)).get());
    assertSame(cachedResult2, cache.get(RuleAnalysisKey.of(buildTarget2)).get());
  }
}

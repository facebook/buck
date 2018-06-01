/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.LEFT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.RIGHT_TARGET;
import static com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory.ROOT_TARGET;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.DummyArtifactCache;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.distributed.testutil.CustomActiongGraphBuilderFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;

public class DistBuildArtifactCacheImplTest {
  private ParallelRuleKeyCalculator<RuleKey> mockRuleKeyCalculator =
      EasyMock.createMock(RuleKeyCalculator.class);
  private ActionGraphBuilder graphBuilder;
  private BuckEventBus eventBus;

  private DistBuildArtifactCacheImpl createTestSubject(
      ArtifactCache remoteCache, Optional<ArtifactCache> localCache) {

    graphBuilder =
        CustomActiongGraphBuilderFactory.createDiamondDependencyBuilderWithChainFromLeaf();
    eventBus = new DefaultBuckEventBus(FakeClock.doNotCare(), new BuildId());

    return new DistBuildArtifactCacheImpl(
        graphBuilder,
        MoreExecutors.newDirectExecutorService(),
        remoteCache,
        eventBus,
        mockRuleKeyCalculator,
        localCache);
  }

  @Test
  public void testIsLocalCachePresent() {
    Assert.assertFalse(
        createTestSubject(new DummyArtifactCache(), Optional.empty()).isLocalCachePresent());
    Assert.assertTrue(
        createTestSubject(new DummyArtifactCache(), Optional.of(new DummyArtifactCache()))
            .isLocalCachePresent());
  }

  @Test
  public void testContains() {
    RuleKey remoteRuleKey = new RuleKey("abcd");
    RuleKey localRuleKey = new RuleKey("ef01");

    DummyArtifactCache remoteCache = new DummyArtifactCache();
    remoteCache.store(
        ArtifactInfo.builder().setRuleKeys(ImmutableList.of(remoteRuleKey)).build(), null);

    DummyArtifactCache localCache = new DummyArtifactCache();
    localCache.store(
        ArtifactInfo.builder().setRuleKeys(ImmutableList.of(localRuleKey)).build(), null);

    ArtifactCacheByBuildRule distBuildCache =
        createTestSubject(remoteCache, Optional.of(localCache));

    BuildRule rootRule = graphBuilder.getRule(BuildTargetFactory.newInstance(ROOT_TARGET));
    BuildRule rightRule = graphBuilder.getRule(BuildTargetFactory.newInstance(RIGHT_TARGET));
    BuildRule leftRule = graphBuilder.getRule(BuildTargetFactory.newInstance(LEFT_TARGET));

    expect(mockRuleKeyCalculator.calculate(eventBus, rootRule))
        .andReturn(Futures.immediateFuture(remoteRuleKey))
        .atLeastOnce();
    expect(mockRuleKeyCalculator.calculate(eventBus, rightRule))
        .andReturn(Futures.immediateFuture(localRuleKey))
        .atLeastOnce();
    expect(mockRuleKeyCalculator.calculate(eventBus, leftRule))
        .andReturn(Futures.immediateFuture(localRuleKey))
        .atLeastOnce();

    replay(mockRuleKeyCalculator);
    Assert.assertTrue(distBuildCache.remoteContains(rootRule));
    Assert.assertFalse(distBuildCache.remoteContains(rightRule));
    Assert.assertFalse(distBuildCache.remoteContains(leftRule));
    Assert.assertFalse(distBuildCache.localContains(rootRule));
    Assert.assertTrue(distBuildCache.localContains(rightRule));
    Assert.assertTrue(distBuildCache.localContains(leftRule));
    verify(mockRuleKeyCalculator);
  }

  @Test
  public void testUpload() throws IOException, ExecutionException, InterruptedException {
    ArtifactCache remoteCache = EasyMock.createMock(ArtifactCache.class);
    ArtifactCache localCache = EasyMock.createMock(ArtifactCache.class);
    ArtifactCacheByBuildRule distBuildCache =
        createTestSubject(remoteCache, Optional.of(localCache));

    RuleKey ruleKey = new RuleKey("abcd");
    BuildRule rootRule = graphBuilder.getRule(BuildTargetFactory.newInstance(ROOT_TARGET));
    expect(mockRuleKeyCalculator.calculate(eventBus, rootRule))
        .andReturn(Futures.immediateFuture(ruleKey))
        .atLeastOnce();

    Capture<LazyPath> fetchPath = EasyMock.newCapture();
    expect(localCache.fetchAsync(eq(rootRule.getBuildTarget()), eq(ruleKey), capture(fetchPath)))
        .andReturn(Futures.immediateFuture(CacheResult.hit("", ArtifactCacheMode.dir)))
        .once();

    expect(remoteCache.store(isA(ArtifactInfo.class), isA(BorrowablePath.class)))
        .andAnswer(
            () -> {
              ArtifactInfo artifactInfo = (ArtifactInfo) EasyMock.getCurrentArguments()[0];
              BorrowablePath storePath = (BorrowablePath) EasyMock.getCurrentArguments()[1];
              Assert.assertEquals(artifactInfo.getRuleKeys(), ImmutableSet.of(ruleKey));
              Assert.assertEquals(storePath.getPath(), fetchPath.getValue().get());
              return Futures.immediateFuture(null);
            })
        .once();

    replay(mockRuleKeyCalculator);
    replay(localCache);
    replay(remoteCache);

    Assert.assertEquals(rootRule, distBuildCache.uploadFromLocal(rootRule).get());

    verify(mockRuleKeyCalculator);
    verify(localCache);
    verify(remoteCache);
  }

  @Test
  public void testPrewarm() {
    ImmutableList<RuleKey> ruleKeys =
        ImmutableList.of(new RuleKey("1234"), new RuleKey("3456"), new RuleKey("5678"));

    ArtifactCache remoteCache = EasyMock.createMock(ArtifactCache.class);
    ArtifactCacheByBuildRule distBuildCache = createTestSubject(remoteCache, Optional.empty());

    BuildTarget rootTarget = BuildTargetFactory.newInstance(ROOT_TARGET);
    BuildTarget rightTarget = BuildTargetFactory.newInstance(RIGHT_TARGET);
    BuildTarget leftTarget = BuildTargetFactory.newInstance(LEFT_TARGET);

    expect(mockRuleKeyCalculator.calculate(eventBus, graphBuilder.getRule(rootTarget)))
        .andReturn(Futures.immediateFuture(ruleKeys.get(0)))
        .atLeastOnce();
    expect(mockRuleKeyCalculator.calculate(eventBus, graphBuilder.getRule(rightTarget)))
        .andReturn(Futures.immediateFuture(ruleKeys.get(1)))
        .atLeastOnce();
    expect(mockRuleKeyCalculator.calculate(eventBus, graphBuilder.getRule(leftTarget)))
        .andReturn(Futures.immediateFuture(ruleKeys.get(2)))
        .atLeastOnce();
    expect(mockRuleKeyCalculator.getAllKnownTargets())
        .andReturn(ImmutableSet.of(rootTarget, rightTarget, leftTarget))
        .once();

    expect(remoteCache.multiContainsAsync(ImmutableSet.of(ruleKeys.get(0), ruleKeys.get(1))))
        .andReturn(
            Futures.immediateFuture(
                ImmutableMap.of(
                    ruleKeys.get(0), CacheResult.miss(), ruleKeys.get(1), CacheResult.miss())))
        .once();
    expect(remoteCache.multiContainsAsync(ImmutableSet.of(ruleKeys.get(2))))
        .andReturn(Futures.immediateFuture(ImmutableMap.of(ruleKeys.get(2), CacheResult.miss())))
        .once();

    replay(mockRuleKeyCalculator);
    distBuildCache.prewarmRemoteContains(
        ImmutableSet.of(graphBuilder.getRule(rootTarget), graphBuilder.getRule(rightTarget)));
    distBuildCache.prewarmRemoteContainsForAllKnownRules();
    verify(mockRuleKeyCalculator);
  }

  private class RuleKeyCalculator extends ParallelRuleKeyCalculator<RuleKey> {
    public RuleKeyCalculator(
        ListeningExecutorService service,
        RuleKeyFactory<RuleKey> ruleKeyFactory,
        RuleDepsCache ruleDepsCache,
        BiFunction<BuckEventBus, BuildRule, Scope> ruleKeyCalculationScope) {
      super(service, ruleKeyFactory, ruleDepsCache, ruleKeyCalculationScope);
    }
  }
}

/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.distributed.build_client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionNotifier;
import com.facebook.buck.util.timing.Clock;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

public class BuildRuleEventManagerTest {
  private static final String BUILD_TARGET_ONE = "buildTargetOne";
  private static final String BUILD_TARGET_TWO = "buildTargetTwo";
  private static final String BUILD_TARGET_THREE = "buildTargetThree";

  public static final int CACHE_SYNCHRONIZATION_SAFETY_MARGIN = 10;
  private BuildRuleEventManager eventManager;
  private RemoteBuildRuleCompletionNotifier remoteBuildRuleCompletionNotifier;
  private Clock clock;

  @Before
  public void setUp() {
    remoteBuildRuleCompletionNotifier =
        EasyMock.createMock(RemoteBuildRuleCompletionNotifier.class);
    clock = EasyMock.createMock(Clock.class);

    // Create partial mock, that only mocks threadSleep method.
    eventManager =
        EasyMock.createMockBuilder(BuildRuleEventManager.class)
            .withConstructor(RemoteBuildRuleCompletionNotifier.class, Clock.class, int.class)
            .withArgs(remoteBuildRuleCompletionNotifier, clock, CACHE_SYNCHRONIZATION_SAFETY_MARGIN)
            .addMockedMethod("threadSleep")
            .createMock();
  }

  @Test
  public void testSetAllBuildRulesFinishedEventReceived() {
    assertFalse(eventManager.allBuildRulesFinishedEventReceived());
    eventManager.recordAllBuildRulesFinishedEvent();
    assertTrue(eventManager.allBuildRulesFinishedEventReceived());
  }

  @Test
  public void testBuildRuleStartedEventsNotifiedImmediately() {
    remoteBuildRuleCompletionNotifier.signalStartedRemoteBuildingOfBuildRule(BUILD_TARGET_ONE);
    EasyMock.expectLastCall();
    remoteBuildRuleCompletionNotifier.signalStartedRemoteBuildingOfBuildRule(BUILD_TARGET_TWO);
    EasyMock.expectLastCall();
    EasyMock.replay(remoteBuildRuleCompletionNotifier);

    eventManager.recordBuildRuleStartedEvent(BUILD_TARGET_ONE);
    eventManager.recordBuildRuleStartedEvent(BUILD_TARGET_TWO);

    EasyMock.verify(remoteBuildRuleCompletionNotifier);
  }

  @Test
  public void testBuildRuleUnlockedEventsNotifiedImmediately() {
    remoteBuildRuleCompletionNotifier.signalUnlockedBuildRule(BUILD_TARGET_ONE);
    EasyMock.expectLastCall();
    remoteBuildRuleCompletionNotifier.signalUnlockedBuildRule(BUILD_TARGET_TWO);
    EasyMock.expectLastCall();
    EasyMock.replay(remoteBuildRuleCompletionNotifier);

    eventManager.recordBuildRuleUnlockedEvent(BUILD_TARGET_ONE);
    eventManager.recordBuildRuleUnlockedEvent(BUILD_TARGET_TWO);

    EasyMock.verify(remoteBuildRuleCompletionNotifier);
  }

  @Test
  public void testPublishesOnlyCacheSynchronizedEvents() {
    // Setup expectations for how long event manager will sleep
    // BUILD_TARGET_ONE/TWO are synchronized at time 12
    EasyMock.expect(clock.currentTimeMillis()).andReturn((long) 12);
    remoteBuildRuleCompletionNotifier.signalCompletionOfBuildRule(BUILD_TARGET_ONE);
    EasyMock.expectLastCall();
    remoteBuildRuleCompletionNotifier.signalCompletionOfBuildRule(BUILD_TARGET_TWO);
    EasyMock.expectLastCall();

    // Nothing to publish at time 14
    EasyMock.expect(clock.currentTimeMillis()).andReturn((long) 14);

    // BUILD_TARGET_THREE is synchronized at time 15
    EasyMock.expect(clock.currentTimeMillis()).andReturn((long) 15);
    remoteBuildRuleCompletionNotifier.signalCompletionOfBuildRule(BUILD_TARGET_THREE);
    EasyMock.expectLastCall();

    EasyMock.replay(clock);
    EasyMock.replay(remoteBuildRuleCompletionNotifier);
    EasyMock.replay(eventManager);

    // Record the receipt of messages from distributed build workers.
    eventManager.recordBuildRuleFinishedEvent(1, BUILD_TARGET_ONE);
    eventManager.recordBuildRuleFinishedEvent(2, BUILD_TARGET_TWO);
    eventManager.recordBuildRuleFinishedEvent(5, BUILD_TARGET_THREE);

    // Call at time 12. Build rules one/two published.
    eventManager.publishCacheSynchronizedBuildRuleFinishedEvents();

    // Call at time 14. Publish nothing
    eventManager.publishCacheSynchronizedBuildRuleFinishedEvents();

    // Call at time 15. Build rule three published.
    eventManager.publishCacheSynchronizedBuildRuleFinishedEvents();

    EasyMock.verify(clock);
    EasyMock.verify(remoteBuildRuleCompletionNotifier);
    EasyMock.verify(eventManager);
  }

  @Test
  public void testWaitsForCacheSynchronizationWhenFlushing() throws InterruptedException {
    // Setup expectations for how long event manager will sleep

    // BUILD_TARGET_ONE synchronizes at time 11, so wait 5 millis.
    EasyMock.expect(clock.currentTimeMillis()).andReturn((long) 6);
    eventManager.threadSleep(5);
    EasyMock.expectLastCall();
    remoteBuildRuleCompletionNotifier.signalCompletionOfBuildRule(BUILD_TARGET_ONE);
    EasyMock.expectLastCall();

    // BUILD_TARGET_TWO synchronizes at time 11, so don't wait
    EasyMock.expect(clock.currentTimeMillis()).andReturn((long) 11);
    remoteBuildRuleCompletionNotifier.signalCompletionOfBuildRule(BUILD_TARGET_TWO);
    EasyMock.expectLastCall();

    // BUILD_TARGET_THREE synchronizes at time 15, so wait 4 millis
    EasyMock.expect(clock.currentTimeMillis()).andReturn((long) 11);
    eventManager.threadSleep(4);
    EasyMock.expectLastCall();
    remoteBuildRuleCompletionNotifier.signalCompletionOfBuildRule(BUILD_TARGET_THREE);
    EasyMock.expectLastCall();

    EasyMock.replay(clock);
    EasyMock.replay(remoteBuildRuleCompletionNotifier);
    EasyMock.replay(eventManager);

    // Record the receipt of messages from distributed build workers.
    eventManager.recordBuildRuleFinishedEvent(1, BUILD_TARGET_ONE);
    eventManager.recordBuildRuleFinishedEvent(1, BUILD_TARGET_TWO);
    eventManager.recordBuildRuleFinishedEvent(5, BUILD_TARGET_THREE);

    // Call at time 11. Build rules one/two after sleep of 5, then three after sleep of 4.
    eventManager.flushAllPendingBuildRuleFinishedEvents();

    // Call again. Nothing should be published.
    eventManager.flushAllPendingBuildRuleFinishedEvents();

    EasyMock.verify(eventManager);
    EasyMock.verify(clock);
  }
}

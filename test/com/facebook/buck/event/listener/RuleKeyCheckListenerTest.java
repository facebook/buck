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

package com.facebook.buck.event.listener;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.event.TopLevelRuleKeyCalculatedEvent;
import com.facebook.buck.util.versioncontrol.FullVersionControlStats;
import com.facebook.buck.util.versioncontrol.VersionControlStatsEvent;
import java.util.ArrayList;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

/** Ensures that RuleKeyCheckListener functions correctly. */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class RuleKeyCheckListenerTest {
  static BuckEventBus buckEventBus;
  static RuleKeyCheckListenerConfig ruleKeyCheckListenerConfig;

  @BeforeClass
  public static void setUpClass() {
    buckEventBus = BuckEventBusForTests.newInstance();
    ruleKeyCheckListenerConfig =
        FakeBuckConfig.builder()
            .setSections(
                "[rulekey_check]",
                "targets_enabled_for = //repo/.*, //repo2/*",
                "endpoint_url = endpoint",
                "cached_targets = //repo/path/to/target:target",
                "unstable_revision_warning = warning")
            .build()
            .getView(RuleKeyCheckListenerConfig.class);
  }

  public static class VersionControlTests {
    RuleKeyCheckListener ruleKeyCheckListener;
    FullVersionControlStats.Builder versionControlStatsBuilder;

    @Before
    public void setUp() {
      ruleKeyCheckListener =
          new RuleKeyCheckListener(ruleKeyCheckListenerConfig, buckEventBus, "username");
      buckEventBus.register(ruleKeyCheckListener);
      versionControlStatsBuilder =
          FullVersionControlStats.builder()
              .addAllPathsChangedInWorkingDirectory(
                  new ArrayList<String>() {
                    {
                      add("path/to/file");
                    }
                  })
              .setCurrentRevisionId("revisionId")
              .setBranchedFromMasterRevisionId("branchedFromMasterId")
              .setBranchedFromMasterTS((long) 1.00);
      ruleKeyCheckListener.versionControlStats = Optional.empty();
    }

    @After
    public void destroy() {
      buckEventBus.unregister(ruleKeyCheckListener);
    }

    @Test
    public void testWorkingDirectoryChangesOnBranch() {
      FullVersionControlStats versionControlStats = versionControlStatsBuilder.build();
      buckEventBus.post(new VersionControlStatsEvent(versionControlStats));
      assertFalse(ruleKeyCheckListener.versionControlStats.isPresent());
    }

    @Test
    public void testWorkingDirectoryChangesNotOnBranch() {
      FullVersionControlStats versionControlStats =
          versionControlStatsBuilder.setBranchedFromMasterRevisionId("revisionId").build();
      buckEventBus.post(new VersionControlStatsEvent(versionControlStats));
      assertFalse(ruleKeyCheckListener.versionControlStats.isPresent());
    }

    @Test
    public void testNoWorkingDirectoryChangesOnBranch() {
      FullVersionControlStats versionControlStats =
          versionControlStatsBuilder.setPathsChangedInWorkingDirectory(new ArrayList<>()).build();
      buckEventBus.post(new VersionControlStatsEvent(versionControlStats));
      assertFalse(ruleKeyCheckListener.versionControlStats.isPresent());
    }

    @Test
    public void testNoWorkingDirectoryChangesNotOnBranch() {
      FullVersionControlStats versionControlStats =
          versionControlStatsBuilder
              .setPathsChangedInWorkingDirectory(new ArrayList<>())
              .setBranchedFromMasterRevisionId("revisionId")
              .build();
      buckEventBus.post(new VersionControlStatsEvent(versionControlStats));
      assertTrue(ruleKeyCheckListener.versionControlStats.isPresent());
    }
  }

  public static class TopLevelRuleKeyMatchTests {
    BuildTarget fakeBuildTarget;
    TopLevelRuleKeyCalculatedEvent event;
    RuleKeyCheckListener ruleKeyCheckListener;

    @Before
    public void setUp() {
      fakeBuildTarget = BuildTargetFactory.newInstance("//repo/path/to/target:target");

      event = new TopLevelRuleKeyCalculatedEvent(fakeBuildTarget, new RuleKey("1234"));

      ruleKeyCheckListener =
          EasyMock.createMockBuilder(RuleKeyCheckListener.class)
              .withConstructor(RuleKeyCheckListenerConfig.class, BuckEventBus.class, String.class)
              .withArgs(ruleKeyCheckListenerConfig, buckEventBus, "username")
              .addMockedMethod("queryEndpoint")
              .createNiceMock();
    }

    @After
    public void destroy() {
      buckEventBus.unregister(ruleKeyCheckListener);
    }

    @Test
    public void testOnTopLevelRuleKeyCalculatedMatch() {
      EasyMock.expect(
              ruleKeyCheckListener.queryEndpoint(
                  event.getFullyQualifiedRuleName(), event.getRulekeyAsString()))
          .andReturn(RuleKeyCheckListener.RuleKeyCheckResult.TOP_LEVEL_RULEKEY_MATCH);
      EasyMock.replay(ruleKeyCheckListener);
      buckEventBus.register(ruleKeyCheckListener);

      FullVersionControlStats versionControlStats =
          FullVersionControlStats.builder()
              .addAllPathsChangedInWorkingDirectory(new ArrayList<>())
              .setCurrentRevisionId("revisionId")
              .setBranchedFromMasterRevisionId("revisionId")
              .setBranchedFromMasterTS((long) 1.00)
              .build();
      buckEventBus.post(new VersionControlStatsEvent(versionControlStats));

      FakeBuckEventListener fakeBuckEventListener = new FakeBuckEventListener();
      buckEventBus.register(fakeBuckEventListener);
      buckEventBus.post(event);

      assertEquals(2, fakeBuckEventListener.getEvents().size());
      assertThat(
          fakeBuckEventListener.getEvents().get(1),
          instanceOf(RuleKeyCheckListener.TopLevelCacheCheckEvent.class));
      RuleKeyCheckListener.TopLevelCacheCheckEvent resultEvent =
          (RuleKeyCheckListener.TopLevelCacheCheckEvent) fakeBuckEventListener.getEvents().get(1);
      assertEquals(
          resultEvent.getTopLevelCacheCheckResult(),
          RuleKeyCheckListener.RuleKeyCheckResult.TOP_LEVEL_RULEKEY_MATCH.name());
    }

    @Test
    public void testOnTopLevelRuleKeyCalculatedNoMatch() {
      EasyMock.expect(
              ruleKeyCheckListener.queryEndpoint(
                  event.getFullyQualifiedRuleName(), event.getRulekeyAsString()))
          .andReturn(RuleKeyCheckListener.RuleKeyCheckResult.TARGET_BUILT_NO_RULEKEY_MATCH);
      EasyMock.replay(ruleKeyCheckListener);
      buckEventBus.register(ruleKeyCheckListener);

      FullVersionControlStats versionControlStats =
          FullVersionControlStats.builder()
              .addAllPathsChangedInWorkingDirectory(new ArrayList<>())
              .setCurrentRevisionId("revisionId")
              .setBranchedFromMasterRevisionId("revisionId")
              .setBranchedFromMasterTS((long) 1.00)
              .build();
      buckEventBus.post(new VersionControlStatsEvent(versionControlStats));

      FakeBuckEventListener fakeBuckEventListener = new FakeBuckEventListener();
      buckEventBus.register(fakeBuckEventListener);
      buckEventBus.post(event);

      System.err.println(fakeBuckEventListener.getEvents());
      assertEquals(2, fakeBuckEventListener.getEvents().size());
      assertThat(
          fakeBuckEventListener.getEvents().get(1),
          instanceOf(RuleKeyCheckListener.TopLevelCacheCheckEvent.class));
      RuleKeyCheckListener.TopLevelCacheCheckEvent resultEvent =
          (RuleKeyCheckListener.TopLevelCacheCheckEvent) fakeBuckEventListener.getEvents().get(1);
      assertEquals(
          resultEvent.getTopLevelCacheCheckResult(),
          RuleKeyCheckListener.RuleKeyCheckResult.TARGET_BUILT_NO_RULEKEY_MATCH.name());
    }

    @Test
    public void testOnTopLevelRuleKeyCalculatedNotBuilt() {
      EasyMock.expect(
              ruleKeyCheckListener.queryEndpoint(
                  event.getFullyQualifiedRuleName(), event.getRulekeyAsString()))
          .andReturn(RuleKeyCheckListener.RuleKeyCheckResult.TARGET_NOT_BUILT);
      EasyMock.replay(ruleKeyCheckListener);
      buckEventBus.register(ruleKeyCheckListener);

      FullVersionControlStats versionControlStats =
          FullVersionControlStats.builder()
              .addAllPathsChangedInWorkingDirectory(new ArrayList<>())
              .setCurrentRevisionId("revisionId")
              .setBranchedFromMasterRevisionId("revisionId")
              .setBranchedFromMasterTS(System.currentTimeMillis() - 2L)
              .build();
      buckEventBus.post(new VersionControlStatsEvent(versionControlStats));

      FakeBuckEventListener fakeBuckEventListener = new FakeBuckEventListener();
      buckEventBus.register(fakeBuckEventListener);
      buckEventBus.post(event);

      assertEquals(3, fakeBuckEventListener.getEvents().size());
      assertThat(
          fakeBuckEventListener.getEvents().get(1),
          instanceOf(RuleKeyCheckListener.TopLevelCacheCheckEvent.class));
      assertThat(fakeBuckEventListener.getEvents().get(2), instanceOf(ConsoleEvent.class));
      RuleKeyCheckListener.TopLevelCacheCheckEvent resultEvent =
          (RuleKeyCheckListener.TopLevelCacheCheckEvent) fakeBuckEventListener.getEvents().get(1);
      assertEquals(
          resultEvent.getTopLevelCacheCheckResult(),
          RuleKeyCheckListener.RuleKeyCheckResult.TARGET_NOT_BUILT.name());
      assertEquals(
          "warning", ((ConsoleEvent) fakeBuckEventListener.getEvents().get(2)).getMessage());
    }
  }

  public static class QueryEndpointTests {
    @Test
    public void testQueryEndpointEndpointConfigURLNotPresent() {
      RuleKeyCheckListenerConfig config =
          FakeBuckConfig.builder()
              .setSections(
                  "[rulekey_check]",
                  "targets_enabled_for = //repo/.*, //repo2/*",
                  "endpoint_url = ")
              .build()
              .getView(RuleKeyCheckListenerConfig.class);
      RuleKeyCheckListener ruleKeyCheckListener =
          new RuleKeyCheckListener(config, buckEventBus, "username");
      assertEquals(
          RuleKeyCheckListener.RuleKeyCheckResult.REQUEST_ERROR,
          ruleKeyCheckListener.queryEndpoint("target", "rulekey"));
    }

    @Test
    public void testQueryEndpointConfigURLKeyMisspelled() {
      RuleKeyCheckListenerConfig config =
          FakeBuckConfig.builder()
              .setSections(
                  "[rulekey_check]",
                  "targets_enabled_for = //repo/.*, //repo2/*",
                  "endpoint = endpoint")
              .build()
              .getView(RuleKeyCheckListenerConfig.class);
      RuleKeyCheckListener ruleKeyCheckListener =
          new RuleKeyCheckListener(config, buckEventBus, "username");
      assertEquals(
          RuleKeyCheckListener.RuleKeyCheckResult.REQUEST_ERROR,
          ruleKeyCheckListener.queryEndpoint("target", "rulekey"));
    }
  }
}

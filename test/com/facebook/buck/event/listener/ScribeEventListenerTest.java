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

package com.facebook.buck.event.listener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildEvent.Started;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.network.FakeScribeLogger;
import com.facebook.buck.util.timing.FakeClock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

public class ScribeEventListenerTest {

  private static final String CATEGORY = "buck_events";
  private static final BuildId BUILD_ID = new BuildId("fake_build_id");
  private static final long NANO_TIME = TimeUnit.SECONDS.toNanos(300);
  private static final long CURRENT_TIME_MILLIS = 1409702151000L;
  private static final FakeClock FAKE_CLOCK =
      FakeClock.builder().currentTimeMillis(CURRENT_TIME_MILLIS).nanoTime(NANO_TIME).build();

  private BuckEventBus eventBus;
  private ScribeEventListener listener;
  private FakeScribeLogger logger;

  @Before
  public void setUp() {
    logger = new FakeScribeLogger();
    ScribeEventListenerConfig config =
        FakeBuckConfig.builder()
            .setSections(
                "[scribe_event_listener]",
                "enabled = true",
                "category = buck_events",
                "events = BuildStarted, BuildFinished, BuildRuleFinished",
                "enabled_build_rule_finished_statuses = FAIL")
            .build()
            .getView(ScribeEventListenerConfig.class);
    listener = new ScribeEventListener(config, logger, MoreExecutors.newDirectExecutorService());
    eventBus = new DefaultBuckEventBus(FAKE_CLOCK, false, BUILD_ID, 1000);
    eventBus.register(listener);
  }

  @Test
  public void testLoggerDispatchesEvents() {
    eventBus.post(BuildEvent.started(Collections.singletonList("arg1")));

    assertEquals(1, logger.getLinesForCategory(CATEGORY).size());
  }

  @Test
  public void testOnlyConfiguredEventNamesAreAllowed() {
    Started started = BuildEvent.started(Collections.singletonList("arg1"));
    eventBus.post(started);
    eventBus.post(BuildEvent.finished(started, ExitCode.SUCCESS));
    eventBus.post(BuildEvent.distBuildStarted());

    assertEquals(2, logger.getLinesForCategory(CATEGORY).size());
  }

  @Test
  public void testJsonSerializationIsCorrect() throws IOException {
    eventBus.post(BuildEvent.started(Arrays.asList("arg1", "arg2")));

    ImmutableList<String> lines = logger.getLinesForCategory(CATEGORY);

    ObjectMapper mapper = new ObjectMapper();
    TypeReference<HashMap<String, Object>> typeRef =
        new TypeReference<HashMap<String, Object>>() {};
    Map<String, Object> map = mapper.readValue(lines.get(0), typeRef);

    assertTrue(map.size() > 0);
    assertEquals(BUILD_ID.toString(), map.get("buildId"));
  }

  @Test
  public void testEventsAreOrdered() throws IOException {

    for (int i = 0; i < 100; i++) {
      eventBus.post(BuildEvent.started(Collections.singletonList(String.valueOf(i))));
    }

    ImmutableList<String> lines = logger.getLinesForCategory(CATEGORY);

    ObjectMapper mapper = new ObjectMapper();
    TypeReference<HashMap<String, Object>> typeRef =
        new TypeReference<HashMap<String, Object>>() {};
    for (int i = 0; i < 100; i++) {
      Map<String, Object> map = mapper.readValue(lines.get(i), typeRef);
      List<String> args = (List<String>) map.get("buildArgs");
      assertEquals(args.get(0), String.valueOf(i));
    }
  }

  @Test
  public void testIsEnabledEvent() {
    assertTrue(listener.isEnabledEvent(finishedEvent(BuildRuleStatus.FAIL)));
    assertFalse(listener.isEnabledEvent(finishedEvent(BuildRuleStatus.SUCCESS)));
  }

  BuildRuleEvent.Finished finishedEvent(BuildRuleStatus status) {
    return BuildRuleEvent.finished(
        BuildRuleEvent.started(new FakeBuildRule("//fake:rule"), new BuildRuleDurationTracker()),
        BuildRuleKeys.of(new RuleKey("aa")),
        status,
        CacheResult.miss(),
        Optional.empty(),
        Optional.empty(),
        UploadToCacheResultType.UNCACHEABLE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}

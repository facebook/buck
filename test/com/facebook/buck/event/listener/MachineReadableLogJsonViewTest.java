/*
 * Copyright 2016-present Facebook, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.JsonMatcher;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.hash.HashCode;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.Random;

public class MachineReadableLogJsonViewTest {

  private static final ObjectWriter WRITER = ObjectMappers.newDefaultInstance()
      .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, false)
      .writerWithView(JsonViews.MachineReadableLog.class);

  private long timestamp;
  private long nanoTime;
  private long threadUserNanoTime;
  private long threadId;
  private BuildId buildId;

  @Before
  public void setUp() {
    Clock clock = new DefaultClock();
    timestamp = clock.currentTimeMillis();
    nanoTime = clock.nanoTime();
    // Not using real value as not all JVMs will support thread user time.
    threadUserNanoTime = new Random().nextLong();
    threadId = 0;
    buildId = new BuildId("Test");
  }

  @Test
  public void testWatchmanEvents() throws Exception {
    WatchmanStatusEvent createEvent = WatchmanStatusEvent.fileCreation("filename_new");
    WatchmanStatusEvent deleteEvent = WatchmanStatusEvent.fileDeletion("filename_del");
    WatchmanStatusEvent overflowEvent = WatchmanStatusEvent.overflow("reason");

    // Configure the events so timestamps etc are there.
    createEvent.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    deleteEvent.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    overflowEvent.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);

    assertJsonEquals("{%s,\"filename\":\"filename_new\"}", WRITER.writeValueAsString(createEvent));
    assertJsonEquals("{%s,\"filename\":\"filename_del\"}", WRITER.writeValueAsString(deleteEvent));
    assertJsonEquals("{%s,\"reason\":\"reason\"}", WRITER.writeValueAsString(overflowEvent));
  }

  @Test
  public void testParsingEvents() throws Exception {
    ParsingEvent symlink = ParsingEvent.symlinkInvalidation("target");
    ParsingEvent envChange = ParsingEvent.environmentalChange("diff");

    symlink.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    envChange.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);

    assertJsonEquals("{%s,\"path\":\"target\"}", WRITER.writeValueAsString(symlink));
    assertJsonEquals("{%s,\"diff\":\"diff\"}", WRITER.writeValueAsString(envChange));
  }

  @Test
  public void testBuildRuleEvent() throws IOException {
    BuildRule rule = FakeBuildRule.newEmptyInstance("//fake:rule");
    BuildRuleEvent.Finished event =
        BuildRuleEvent.finished(
            rule,
            BuildRuleKeys.builder()
                .setRuleKey(new RuleKey("aaaa"))
                .setInputRuleKey(Optional.of(new RuleKey("bbbb")))
                .build(),
            BuildRuleStatus.SUCCESS,
            CacheResult.of(
                CacheResultType.MISS,
                Optional.of("my-secret-source"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()),
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.of(HashCode.fromString("abcd42")),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    event.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
    String message = WRITER.writeValueAsString(event);
    assertJsonEquals(
        "{%s,\"status\":\"SUCCESS\",\"cacheResult\":{\"type\":\"MISS\"," +
            "\"cacheSource\":\"my-secret-source\"}," +
            "\"buildRule\":{\"name\":\"//fake:rule\"}," +
            "\"ruleKeys\":{\"ruleKey\":{\"hashCode\":\"aaaa\"}," +
            "\"inputRuleKey\":{\"hashCode\":\"bbbb\"}}," +
            "\"outputHash\":\"abcd42\"},",
        message);
  }

  private void assertJsonEquals(String expected, String actual) throws IOException {
    String commonHeader = String.format("\"timestamp\":%d", timestamp);
    assertThat(actual, new JsonMatcher(String.format(expected, commonHeader)));
  }

}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.RuleKey;
import com.google.common.collect.ImmutableSet;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;

public class HttpArtifactCacheEventTest {

  private static final ImmutableSet<RuleKey> TEST_RULE_KEYS = ImmutableSet.of(
      new RuleKey("1234567890"),
      new RuleKey("123456"),
      new RuleKey("1234")
  );

  private static final RuleKey TEST_RULE_KEY = new RuleKey("4321");

  @Test
  public void storeDataContainsRuleKeys() throws IOException {
    HttpArtifactCacheEvent.Finished finishedEvent = createStoreBuilder(TEST_RULE_KEYS).build();
    configureEvent(finishedEvent);
    Assert.assertEquals(TEST_RULE_KEYS, finishedEvent.getStoreData().getRuleKeys());
  }

  @Test
  public void fetchDataContainsRuleKey() throws IOException {
    HttpArtifactCacheEvent.Finished finishedEvent = createFetchBuilder(TEST_RULE_KEY).build();
    configureEvent(finishedEvent);
    Assert.assertEquals(TEST_RULE_KEY, finishedEvent.getFetchData().getRequestedRuleKey());
  }

  private static HttpArtifactCacheEvent.Finished.Builder createStoreBuilder(
      ImmutableSet<RuleKey> ruleKeys) {

    HttpArtifactCacheEvent.Scheduled scheduledEvent =
        HttpArtifactCacheEvent.newStoreScheduledEvent(
            Optional.of("target"), ruleKeys);
    HttpArtifactCacheEvent.Started startedEvent = HttpArtifactCacheEvent.newStoreStartedEvent(
        scheduledEvent);
    configureEvent(startedEvent);
    return HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
  }

  private static HttpArtifactCacheEvent.Finished.Builder createFetchBuilder(RuleKey ruleKey) {
    HttpArtifactCacheEvent.Started startedEvent = HttpArtifactCacheEvent.newFetchStartedEvent(
        ruleKey);
    configureEvent(startedEvent);
    HttpArtifactCacheEvent.Finished.Builder builder =
        HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
    builder.getFetchBuilder().setFetchResult(CacheResult.hit("super source"));
    return builder;
  }

  private static void configureEvent(AbstractBuckEvent event) {
    event.configure(-1, -1, -1, -1, new BuildId());
  }
}

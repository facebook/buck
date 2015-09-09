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

package com.facebook.buck.event;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.RuleKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.io.IOException;

public class HttpArtifactCacheEventTest {

  private static final ObjectMapper JSON_CONVERTER = new ObjectMapper();

  private static final ImmutableList<RuleKey> TEST_RULE_KEYS = ImmutableList.of(
      new RuleKey("1234567890"),
      new RuleKey("123456"),
      new RuleKey("1234")
  );
  private static final String TEST_RULE_KEYS_JSON = "[\"1234567890\",\"123456\",\"1234\"]";

  @Test
  public void jsonRepresentationContainsAllRuleKeysWithTransform() throws IOException {
    Iterable<String> ruleKeysAsStrings = Iterables.transform(
        TEST_RULE_KEYS,
        Functions.toStringFunction());

    HttpArtifactCacheEvent.Finished finishedEvent = createBuilder()
        .setRuleKeys(ruleKeysAsStrings)
        .build();
    configureEvent(finishedEvent);
    String json = JSON_CONVERTER.writeValueAsString(finishedEvent);
    assertTrue(json.contains(TEST_RULE_KEYS_JSON));
  }

  @Test
  public void jsonRepresentationContainsAllRuleKeys() throws IOException {
    HttpArtifactCacheEvent.Finished finishedEvent = createBuilder()
        .setRuleKeys(TEST_RULE_KEYS)
        .build();
    configureEvent(finishedEvent);
    String json = JSON_CONVERTER.writeValueAsString(finishedEvent);
    assertTrue(json.contains(TEST_RULE_KEYS_JSON));
  }

  private static HttpArtifactCacheEvent.Finished.Builder createBuilder() {
    HttpArtifactCacheEvent.Started startedEvent = HttpArtifactCacheEvent.newStoreStartedEvent();
    configureEvent(startedEvent);
    return HttpArtifactCacheEvent.newFinishedEventBuilder(startedEvent);
  }

  private static void configureEvent(HttpArtifactCacheEvent event) {
    event.configure(-1, -1, -1, new BuildId());
  }
}

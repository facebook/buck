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

package com.facebook.buck.plugin.intellij.commands.event;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import javax.annotation.Nullable;

public class EventFactory {

  public static final String RULE_START = "BuildRuleStarted";
  public static final String RULE_END = "BuildRuleFinished";
  public static final String TEST_RESULTS_AVAILABLE = "ResultsAvailable";
  private static final Logger LOG = Logger.getInstance(EventFactory.class);

  private EventFactory() {}

  @Nullable
  public static Event factory(JsonObject object) {
    String type = object.get("type").getAsString();
    int timestamp = object.get("timestamp").getAsInt();
    String buildId = object.get("buildId").getAsString();
    int threadId = object.get("threadId").getAsInt();
    if (RULE_START.equals(type)) {
      String name = object.get("buildRule").getAsJsonObject().get("name").getAsString();
      return new RuleStart(timestamp, buildId, threadId, name);
    } else if (RULE_END.equals(type)) {
      String name = object.get("buildRule").getAsJsonObject().get("name").getAsString();
      String status = object.get("status").getAsString();
      String cache = object.get("cacheResult").getAsString();
      return new RuleEnd(timestamp, buildId, threadId, name, status, cache.equals("HIT"));
    } else if (TEST_RESULTS_AVAILABLE.equals(type)) {
      return TestResultsAvailable.factory(object, timestamp, buildId, threadId);
    } else {
      LOG.warn("Unhandled message: " + object.toString());
    }
    return null;
  }
}

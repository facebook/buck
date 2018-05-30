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

package com.facebook.buck.core.model.actiongraph.computation;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.TestWithBuckd;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.trace.ChromeTraceParser;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ActionGraphCacheIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public TestWithBuckd testWithBuckd = new TestWithBuckd(tmp);

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenarioWithoutDefaultCell(
            this, "action_graph_cache", tmp);
    workspace.setUp();
  }

  enum ActionGraphCacheStatus {
    HIT,
    MISS_CACHE_EMPTY,
    MISS_TARGET_GRAPH_MISMATCH,
  }

  @Test
  public void specifyingSkipActionGraphCacheDoesNotInvalidateTheActionGraphCache()
      throws InterruptedException, IOException {
    try (TestContext context = new TestContext()) {
      workspace
          .runBuckdCommand(context, "build", "//:pretend_this_is_an_expensive_rule")
          .assertSuccess();
      assertEquals(
          "Should be a fresh daemon, so the ActionGraph cache should be empty.",
          ActionGraphCacheStatus.MISS_CACHE_EMPTY,
          getActionGraphCacheStatus());

      workspace
          .runBuckdCommand(context, "build", "//:pretend_this_is_an_expensive_rule")
          .assertSuccess();
      assertEquals(
          "Rebuilding the rule should hit the cache.",
          ActionGraphCacheStatus.HIT,
          getActionGraphCacheStatus());

      workspace
          .runBuckdCommand(context, "build", "//:pretend_this_is_a_cheap_rule")
          .assertSuccess();
      assertEquals(
          "Building a different rule as a non-oneoff should invalidate the cache.",
          ActionGraphCacheStatus.MISS_TARGET_GRAPH_MISMATCH,
          getActionGraphCacheStatus());

      workspace
          .runBuckdCommand(context, "build", "//:pretend_this_is_an_expensive_rule")
          .assertSuccess();
      assertEquals(
          "Building a different rule as a non-oneoff should invalidate the cache again.",
          ActionGraphCacheStatus.MISS_TARGET_GRAPH_MISMATCH,
          getActionGraphCacheStatus());

      workspace
          .runBuckdCommand(
              context,
              "build",
              "--config",
              "client.skip-action-graph-cache=true",
              "//:pretend_this_is_a_cheap_rule")
          .assertSuccess();
      assertEquals(
          "Building a different rule as a oneoff will still be a mismatch.",
          ActionGraphCacheStatus.MISS_TARGET_GRAPH_MISMATCH,
          getActionGraphCacheStatus());

      workspace
          .runBuckdCommand(context, "build", "//:pretend_this_is_an_expensive_rule")
          .assertSuccess();
      assertEquals(
          "The ActionGraph for the expensive rule should still be in cache.",
          ActionGraphCacheStatus.HIT,
          getActionGraphCacheStatus());
    }
  }

  @SuppressWarnings("unchecked")
  private static final ChromeTraceParser.ChromeTraceEventMatcher<ActionGraphCacheStatus>
      ACTION_GRAPH_CACHE_STATUS_MATCHER =
          (json, name) -> {
            if (!"action_graph_cache".equals(name)) {
              return Optional.empty();
            }

            Object argsEl = json.get("args");
            if (!(argsEl instanceof Map)
                || ((Map<String, Object>) argsEl).get("hit") == null
                || !((((Map<String, Object>) argsEl)).get("hit") instanceof Boolean)) {
              return Optional.empty();
            }

            Map<String, Object> args = (Map<String, Object>) argsEl;
            boolean isHit = (Boolean) args.get("hit");
            if (isHit) {
              return Optional.of(ActionGraphCacheStatus.HIT);
            } else {
              boolean cacheWasEmpty = (Boolean) args.get("cacheWasEmpty");
              return Optional.of(
                  cacheWasEmpty
                      ? ActionGraphCacheStatus.MISS_CACHE_EMPTY
                      : ActionGraphCacheStatus.MISS_TARGET_GRAPH_MISMATCH);
            }
          };

  private ActionGraphCacheStatus getActionGraphCacheStatus()
      throws InterruptedException, IOException {
    Map<ChromeTraceParser.ChromeTraceEventMatcher<?>, Object> results =
        workspace.parseTraceFromMostRecentBuckInvocation(
            ImmutableSet.of(ACTION_GRAPH_CACHE_STATUS_MATCHER));
    return ChromeTraceParser.getResultForMatcher(ACTION_GRAPH_CACHE_STATUS_MATCHER, results).get();
  }
}

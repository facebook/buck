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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.TopLevelRuleKeyCalculatedEvent;
import com.facebook.buck.util.versioncontrol.VersionControlStatsEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.Subscribe;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Listener that waits for the top level rule key to be calculated and checks to see if the target
 * was cached, if this is a "clean" build (no working directory changes and on master) and posts an
 * event to {@link ScubaBuildListener} with the result to send to log to Scuba.
 */
public class RuleKeyCheckListener implements BuckEventListener {
  static final Logger LOG = Logger.get(RuleKeyCheckListener.class);
  Optional<String> cleanRevision; // Set when there are no working directory changes and on master.
  boolean isTopLevelResultCalculated;
  Optional<TopLevelRuleKeyCalculatedEvent> topLevelRuleKeyCalculatedEvent;
  RuleKeyCheckListenerConfig ruleKeyCheckListenerConfig;
  BuckEventBus buckEventBus;

  /** Event posted once result is received from the backend. */
  public class TopLevelCacheCheckEvent extends AbstractBuckEvent {
    @Override
    public String getEventName() {
      return "TopLevelCacheCheckEvent";
    }

    @Override
    protected String getValueString() {
      return topLevelCacheCheckResult.name();
    }

    private RuleKeyCheckListener.RuleKeyCheckResult topLevelCacheCheckResult;

    public TopLevelCacheCheckEvent(
        RuleKeyCheckListener.RuleKeyCheckResult topLevelCacheCheckResult) {
      super(EventKey.unique());
      this.topLevelCacheCheckResult = topLevelCacheCheckResult;
    }

    public String getTopLevelCacheCheckResult() {
      return topLevelCacheCheckResult.name();
    }
  }

  /** Represents results from the backend. */
  public enum RuleKeyCheckResult {
    TOP_LEVEL_RULEKEY_MATCH,
    TARGET_BUILT_NO_RULEKEY_MATCH,
    TARGET_NOT_BUILT,
    BACKEND_ERROR,
    REQUEST_ERROR
  }

  public RuleKeyCheckListener(
      RuleKeyCheckListenerConfig ruleKeyCheckListenerConfig, BuckEventBus buckEventBus) {
    this.ruleKeyCheckListenerConfig = ruleKeyCheckListenerConfig;
    this.cleanRevision = Optional.empty();
    this.topLevelRuleKeyCalculatedEvent = Optional.empty();
    this.buckEventBus = buckEventBus;
    this.isTopLevelResultCalculated = false;
  }

  RuleKeyCheckResult queryEndpoint(String target, String rulekey) {
    FormBody.Builder builder =
        new FormBody.Builder()
            .add("revision", cleanRevision.orElse(""))
            .add("rule_name", target)
            .add("rule_key", rulekey);
    try {
      Request request =
          new Request.Builder()
              .url(ruleKeyCheckListenerConfig.getEndpoint().get())
              .post(builder.build())
              .build();
      OkHttpClient okHttpClient = new OkHttpClient();
      try (Response response = okHttpClient.newCall(request).execute()) {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(Objects.requireNonNull(response.body()).byteStream());
        if (root.get("error") != null) {
          LOG.debug("Endpoint error: %s", root.get("error").asText());
          return RuleKeyCheckResult.BACKEND_ERROR;
        }

        if (root.get("success") != null) {
          String success = root.get("success").asText();
          return RuleKeyCheckResult.valueOf(success);
        }
      }
    } catch (Exception e) {
      LOG.debug("Error querying endpoint.");
    }

    return RuleKeyCheckResult.REQUEST_ERROR;
  }

  private void tryQueryEndpointAndPostResult() {
    if (cleanRevision.isPresent() && topLevelRuleKeyCalculatedEvent.isPresent()) {
      TopLevelRuleKeyCalculatedEvent event = topLevelRuleKeyCalculatedEvent.get();
      boolean didTargetMatch = false;
      for (Pattern pattern : ruleKeyCheckListenerConfig.getTargetsEnabledFor()) {
        if (pattern.matcher(event.getFullyQualifiedRuleName()).matches()) {
          didTargetMatch = true;
          break;
        }
      }

      if (didTargetMatch) {
        RuleKeyCheckResult result =
            queryEndpoint(event.getFullyQualifiedRuleName(), event.getRulekeyAsString());
        isTopLevelResultCalculated = true;
        buckEventBus.post(new TopLevelCacheCheckEvent(result));
      }
    }
  }

  /**
   * This function listens for the event from the builder to see if the top level rule key was built
   * and sets {@link RuleKeyCheckListener#topLevelRuleKeyCalculatedEvent} and tries to query the
   * backend.
   *
   * @param event is variable with the information posted from the builder.
   */
  @Subscribe
  public void onTopLevelRuleKeyCalculated(TopLevelRuleKeyCalculatedEvent event) {
    topLevelRuleKeyCalculatedEvent = Optional.of(event);
    if (!isTopLevelResultCalculated) {
      tryQueryEndpointAndPostResult();
    }
  }

  /**
   * This function listens for events from version control and sets {@link
   * RuleKeyCheckListener#cleanRevision} if there are no working directory changes and if the user
   * is on master and tries to query the backend.
   *
   * @param versionControlStatsEvent contains the data sent.
   */
  @Subscribe
  public synchronized void versionControlStats(VersionControlStatsEvent versionControlStatsEvent) {
    if (versionControlStatsEvent.getVersionControlStats().getPathsChangedInWorkingDirectory().size()
            == 0
        && versionControlStatsEvent
            .getVersionControlStats()
            .getCurrentRevisionId()
            .equals(
                versionControlStatsEvent.getVersionControlStats().getBranchedFromMasterRevisionId())
        && !isTopLevelResultCalculated) {
      cleanRevision =
          Optional.of(versionControlStatsEvent.getVersionControlStats().getCurrentRevisionId());
      tryQueryEndpointAndPostResult();
    }
  }
}

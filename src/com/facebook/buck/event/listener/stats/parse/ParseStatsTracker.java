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
package com.facebook.buck.event.listener.stats.parse;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.listener.util.EventInterval;
import com.facebook.buck.event.listener.util.ProgressEstimator;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;

/** Tracks the parsing state. */
public class ParseStatsTracker {
  private final ConcurrentLinkedDeque<AbstractBuckEvent> parseStarted;
  private final ConcurrentLinkedDeque<AbstractBuckEvent> parseFinished;

  private volatile long parseStartedTime = Long.MAX_VALUE;
  private volatile long parseFinishedTime = Long.MIN_VALUE;

  @Nullable private volatile ProgressEstimator progressEstimator;

  private final ConcurrentLinkedQueue<Listener> listeners = new ConcurrentLinkedQueue<>();

  /** Listeners receive callbacks for interesting events. */
  public interface Listener {
    void parseFinished();
  }

  public ParseStatsTracker() {
    this.parseStarted = new ConcurrentLinkedDeque<>();
    this.parseFinished = new ConcurrentLinkedDeque<>();
  }

  /** Set the progress estimator. Some parse events will be forwarded to it. */
  public void setProgressEstimator(ProgressEstimator progressEstimator) {
    // TODO(cjhopman): This should track progress itself.
    Preconditions.checkState(this.progressEstimator == null);
    this.progressEstimator = progressEstimator;
  }

  public void registerListener(Listener listener) {
    listeners.add(listener);
  }

  /** Get the parse interval. */
  public EventInterval getInterval() {
    // TODO(cjhopman): Is this actually correct? Who knows. It has the same problem as above.
    if (parseStarted.isEmpty()) {
      return EventInterval.of(OptionalLong.empty(), OptionalLong.empty());
    } else if (parseStarted.size() != parseFinished.size()) {
      return EventInterval.of(OptionalLong.of(parseStartedTime), OptionalLong.empty());
    } else {
      return EventInterval.of(
          OptionalLong.of(parseStartedTime), OptionalLong.of(parseFinishedTime));
    }
  }

  @Subscribe
  private void ruleParseFinished(ParseBuckFileEvent.Finished ruleParseFinished) {
    if (progressEstimator == null) {
      return;
    }
    progressEstimator.didParseBuckRules(ruleParseFinished.getNumRules());
  }

  @Subscribe
  private void parseStarted(ParseEvent.Started started) {
    handleStartedEvent(started);
  }

  @Subscribe
  private void parseFinished(ParseEvent.Finished finished) {
    handleFinishedEvent(finished);
  }

  // TODO(cjhopman): It makes no sense to be listening to the ProjectBuildFileParseEvents here, just
  // the ParseEvents should be fine.
  @Subscribe
  private void fileParseStarted(ProjectBuildFileParseEvents.Started started) {
    handleStartedEvent(started);
  }

  @Subscribe
  private void fileParseFinished(ProjectBuildFileParseEvents.Finished finished) {
    handleFinishedEvent(finished);
  }

  private void handleStartedEvent(AbstractBuckEvent started) {
    parseStarted.add(started);
    parseStartedTime = Math.min(parseStartedTime, started.getTimestampMillis());
  }

  private void handleFinishedEvent(AbstractBuckEvent finished) {
    parseFinished.add(finished);
    parseFinishedTime = Math.max(parseFinishedTime, finished.getTimestampMillis());

    // TODO(cjhopman): This is only correct if no parses can start after this point.
    if (parseFinished.size() == parseStarted.size()) {
      listeners.forEach(Listener::parseFinished);
    }
  }
}

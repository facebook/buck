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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.support.jvm.GCCollectionEvent;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.eventbus.Subscribe;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.stringtemplate.v4.ST;

/**
 * This class listens for {@link GCCollectionEvent} and displays a warning message to the user if
 * the percentage of total build time spent in GC is greater than a predefined threshold.
 */
public class GCTimeSpentListener implements BuckEventListener {
  private final BuckEventBus buckEventBus;
  private long commandStartedTimestampMillis;
  private final ExecutionEnvironment executionEnvironment;
  private final GCTimeSpentListenerConfig config;
  private boolean didPrintWarning;
  private long durationInMillis;
  private final int thresholdPercentage;
  private final long thresholdInMillis;

  public GCTimeSpentListener(
      BuckEventBus buckEventBus,
      GCTimeSpentListenerConfig config,
      ExecutionEnvironment executionEnvironment) {
    durationInMillis = 0;
    this.buckEventBus = buckEventBus;
    this.config = config;
    this.didPrintWarning = false;
    this.executionEnvironment = executionEnvironment;
    this.thresholdPercentage = config.getThresholdPercentage();
    this.thresholdInMillis = TimeUnit.SECONDS.toMillis(config.getThresholdInSec());
  }

  private void postWarnings() {
    buckEventBus.post(
        ConsoleEvent.warning(
            String.format(
                didPrintWarning ? "Total time spent in GC : %ds" : "Spent %ds in GC",
                TimeUnit.MILLISECONDS.toSeconds(durationInMillis))));
    buckEventBus.post(
        ConsoleEvent.warning(
            String.format(
                "%s",
                new ST(
                        didPrintWarning
                            ? config.getExcessTimeWarningAtEndTemplate()
                            : config.getExcessTimeWarningAtThresholdTemplate(),
                        '{',
                        '}')
                    .add(
                        "total_system_memory",
                        SizeUnit.toHumanReadableString(
                            SizeUnit.getHumanReadableSize(
                                executionEnvironment.getTotalMemory(), SizeUnit.BYTES),
                            Locale.getDefault()))
                    .add(
                        "max_jvm_heap",
                        SizeUnit.toHumanReadableString(
                            SizeUnit.getHumanReadableSize(
                                Runtime.getRuntime().maxMemory(), SizeUnit.BYTES),
                            Locale.getDefault()))
                    .render())));
  }

  /**
   * This function listens for {@link GCCollectionEvent}, incrementally adds the value to {@link
   * #durationInMillis} and displays a warning to the user if the percentage of time spent in GC is
   * more than a set threshold.
   *
   * @param gcCollectionEvent contains data related to time spent in GC.
   */
  @Subscribe
  public synchronized void onGCCollectionEvent(GCCollectionEvent gcCollectionEvent) {
    durationInMillis += gcCollectionEvent.getDurationInMillis();
    if (!didPrintWarning
        && durationInMillis > thresholdInMillis
        && (durationInMillis * 100f)
                / (gcCollectionEvent.getTimestampMillis() - commandStartedTimestampMillis)
            > thresholdPercentage) {
      postWarnings();
      didPrintWarning = true;
    }
  }

  /**
   * This function listens for {@link CommandEvent.Started} and stores its timestamp.
   *
   * @param started contains the timestamp for when the command started.
   */
  @Subscribe
  public void onCommandStarted(CommandEvent.Started started) {
    commandStartedTimestampMillis = started.getTimestampMillis();
  }

  /**
   * This function listens for {@link CommandEvent.Finished} and prints the warning message with the
   * final amount of time spent in GC if it previously crossed the threshold.
   *
   * @param finished contains data about the finished command.
   */
  @Subscribe
  public void onCommandFinished(CommandEvent.Finished finished) {
    if (didPrintWarning) {
      postWarnings();
    }
  }
}

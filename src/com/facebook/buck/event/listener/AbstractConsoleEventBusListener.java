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
package com.facebook.buck.event.listener;

import com.facebook.buck.cli.InstallEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.ActionGraphEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;

import java.io.Closeable;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.logging.Level;

import javax.annotation.Nullable;

/**
 * Base class for {@link BuckEventListener}s responsible for outputting information about the
 * running build to {@code stderr}.
 */
public abstract class AbstractConsoleEventBusListener implements BuckEventListener, Closeable {
  protected static final DecimalFormat TIME_FORMATTER = new DecimalFormat("0.0s");
  protected static final long UNFINISHED_EVENT_PAIR = -1;
  protected final Console console;
  protected final Clock clock;
  protected final Ansi ansi;

  @Nullable
  protected volatile ProjectBuildFileParseEvents.Started projectBuildFileParseStarted;
  @Nullable
  protected volatile ProjectBuildFileParseEvents.Finished projectBuildFileParseFinished;

  @Nullable
  protected volatile ParseEvent.Started parseStarted;
  @Nullable
  protected volatile ParseEvent.Finished parseFinished;

  @Nullable
  protected volatile ActionGraphEvent.Started actionGraphStarted;
  @Nullable
  protected volatile ActionGraphEvent.Finished actionGraphFinished;

  @Nullable
  protected volatile BuildEvent.Started buildStarted;
  @Nullable
  protected volatile BuildEvent.Finished buildFinished;

  @Nullable
  protected volatile InstallEvent.Started installStarted;
  @Nullable
  protected volatile InstallEvent.Finished installFinished;

  protected volatile Optional<Integer> ruleCount = Optional.absent();

  public AbstractConsoleEventBusListener(Console console, Clock clock) {
    this.console = console;
    this.clock = clock;
    this.ansi = console.getAnsi();

    this.projectBuildFileParseStarted = null;
    this.projectBuildFileParseFinished = null;

    this.parseStarted = null;
    this.parseFinished = null;

    this.actionGraphStarted = null;
    this.actionGraphFinished = null;

    this.buildStarted = null;
    this.buildFinished = null;

    this.installStarted = null;
    this.installFinished = null;
  }

  protected String formatElapsedTime(long elapsedTimeMs) {
    return TIME_FORMATTER.format(elapsedTimeMs / 1000.0);
  }


  /**
   * Adds a line about a pair of start and finished events to lines.
   *
   * @param prefix Prefix to print for this event pair.
   * @param suffix Suffix to print for this event pair.
   * @param currentMillis The current time in milliseconds.
   * @param offsetMs Offset to remove from calculated time.  Set this to a non-zero value if the
   *     event pair would contain another event.  For example, build time includes parse time, but
   *     to make the events easier to reason about it makes sense to pull parse time out of build
   *     time.
   * @param startEvent The started event.
   * @param finishedEvent The finished event.
   * @param lines The builder to append lines to.
   * @return The amount of time between start and finished if finished is present,
   *    otherwise {@link AbstractConsoleEventBusListener#UNFINISHED_EVENT_PAIR}.
   */
  protected long logEventPair(String prefix,
      Optional<String> suffix,
      long currentMillis,
      long offsetMs,
      @Nullable BuckEvent startEvent,
      @Nullable BuckEvent finishedEvent,
      ImmutableList.Builder<String> lines) {
    long result = UNFINISHED_EVENT_PAIR;
    if (startEvent == null) {
      return result;
    }
    String parseLine = (finishedEvent != null ? "[-] " : "[+] ") + prefix + "...";
    long elapsedTimeMs;
    if (finishedEvent != null) {
      elapsedTimeMs = finishedEvent.getTimestamp() - startEvent.getTimestamp();
      parseLine += "FINISHED ";
      result = elapsedTimeMs;
    } else {
      elapsedTimeMs = currentMillis - startEvent.getTimestamp();
    }
    parseLine += formatElapsedTime(elapsedTimeMs - offsetMs);
    if (suffix.isPresent()) {
      parseLine += " " + suffix.get();
    }
    lines.add(parseLine);

    return result;
  }

  /**
   * Formats a {@link ConsoleEvent} and adds it to {@code lines}.
   */
  protected void formatConsoleEvent(ConsoleEvent logEvent, ImmutableList.Builder<String> lines) {
    String formattedLine = "";
    if (logEvent.getLevel().equals(Level.INFO)) {
      formattedLine = logEvent.getMessage();
    } else if (logEvent.getLevel().equals(Level.WARNING)) {
      formattedLine = ansi.asWarningText(logEvent.getMessage());
    } else if (logEvent.getLevel().equals(Level.SEVERE)) {
      formattedLine = ansi.asErrorText(logEvent.getMessage());
    }
    if (!formattedLine.isEmpty()) {
      // Split log messages at newlines and add each line individually to keep the line count
      // consistent.
      lines.addAll(Splitter.on("\n").split(formattedLine));
    }
  }

  @Subscribe
  public void projectBuildFileParseStarted(ProjectBuildFileParseEvents.Started started) {
    projectBuildFileParseStarted = started;
  }

  @Subscribe
  public void projectBuildFileParseFinished(ProjectBuildFileParseEvents.Finished finished) {
    projectBuildFileParseFinished = finished;
  }

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    parseStarted = started;
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    parseFinished = finished;
  }

  @Subscribe
  public void actionGraphStarted(ActionGraphEvent.Started started) {
    actionGraphStarted = started;
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraphFinished = finished;
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    buildStarted = started;
  }

  @Subscribe
  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    ruleCount = Optional.of(calculated.getNumRules());
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    buildFinished = finished;
  }

  @Subscribe
  public void installStarted(InstallEvent.Started started) {
    installStarted = started;
  }

  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    installFinished = finished;
  }

  @Override
  public void outputTrace(BuildId buildId) {}

  @Override
  public void close() throws IOException {
  }
}

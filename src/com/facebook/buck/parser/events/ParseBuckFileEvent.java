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

package com.facebook.buck.parser.events;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.google.common.base.Objects;
import java.nio.file.Path;
import java.util.Optional;

/** Base class for events about parsing build files */
public abstract class ParseBuckFileEvent extends AbstractBuckEvent implements WorkAdvanceEvent {
  private final Path buckFilePath;
  private final Class<? extends ProjectBuildFileParser> parserClass;

  protected ParseBuckFileEvent(
      EventKey eventKey, Path buckFilePath, Class<? extends ProjectBuildFileParser> parserClass) {
    super(eventKey);
    this.buckFilePath = buckFilePath;
    this.parserClass = parserClass;
  }

  /** @return Path to a build file being parsed */
  public Path getBuckFilePath() {
    return buckFilePath;
  }

  /** @return Java class of parser implementation used to parse this build file */
  public Class<? extends ProjectBuildFileParser> getParserClass() {
    return parserClass;
  }

  @Override
  public String getValueString() {
    return buckFilePath.toString();
  }

  /**
   * Create an event when parsing of build file starts
   *
   * @param buckFilePath Path to a build file that is about to start parsing
   * @param parserClass Java class of a parser implementation
   */
  public static Started started(
      Path buckFilePath, Class<? extends ProjectBuildFileParser> parserClass) {
    return new Started(buckFilePath, parserClass);
  }

  /**
   * Create an event when parsing of build file finishes
   *
   * @param started Event created when corresponding build file parsing was started
   * @param rulesCount Total number of rules parsed from this build file
   * @param processedBytes Total number of bytes read while parsing this build file, if applicable
   * @param profile This is the value of getProfile() from PythonDSL parser result. TODO(buck_team)
   *     Update description with real meaning
   */
  public static Finished finished(
      Started started, int rulesCount, long processedBytes, Optional<String> profile) {
    return new Finished(started, rulesCount, processedBytes, profile);
  }

  /** The event raised when build file parsing is started */
  public static class Started extends ParseBuckFileEvent {
    protected Started(Path buckFilePath, Class<? extends ProjectBuildFileParser> parserClass) {
      super(EventKey.unique(), buckFilePath, parserClass);
    }

    @Override
    public String getEventName() {
      return "ParseBuckFileStarted";
    }
  }

  /** The event raised when build file parsing is finished */
  public static class Finished extends ParseBuckFileEvent {
    private final int rulesCount;
    private final long processedBytes;
    private final Optional<String> profile;

    protected Finished(
        Started started, int rulesCount, long processedBytes, Optional<String> profile) {
      super(started.getEventKey(), started.getBuckFilePath(), started.getParserClass());
      this.rulesCount = rulesCount;
      this.processedBytes = processedBytes;
      this.profile = profile;
    }

    @Override
    public String getEventName() {
      return "ParseBuckFileFinished";
    }

    /** @return Number of targets parsed from this build file */
    public int getNumRules() {
      return rulesCount;
    }

    /** @return Number of bytes read while parsing this build file, if applicable */
    public long getProcessedBytes() {
      return processedBytes;
    }

    public Optional<String> getProfile() {
      return profile;
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }
      // Because super.equals compares the EventKey, getting here means that we've somehow managed
      // to create 2 Finished events for the same Started event.
      throw new UnsupportedOperationException("Multiple conflicting Finished events detected.");
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), getNumRules());
    }
  }
}

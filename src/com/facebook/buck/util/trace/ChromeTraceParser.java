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

package com.facebook.buck.util.trace;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Event-driven parser for <a
 * href="https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/preview">
 * Chrome traces</a>.
 */
public class ChromeTraceParser {

  /** Extracts data of interest if it finds a Chrome trace event of the type it is looking for. */
  public interface ChromeTraceEventMatcher<T> {
    /**
     * Tests the specified event to see if it is a match. Its name has already been extracted, for
     * convenience. If it is a match, it should return the data of interest in the return value. If
     * not, then it should return {@link Optional#empty()}.
     */
    Optional<T> test(Map<String, Object> event, String name);
  }

  /**
   * Tries to extract the command that was used to trigger the invocation of Buck that generated the
   * trace. If found, it returns the command as an opaque string.
   */
  @SuppressWarnings("unchecked")
  public static final ChromeTraceEventMatcher<String> COMMAND =
      (json, name) -> {
        Object rawArgs = json.get("args");
        if (!(rawArgs instanceof Map)) {
          return Optional.empty();
        }
        Map<String, Object> args = (Map<String, Object>) rawArgs;
        if (args.get("command_args") == null || !(args.get("command_args") instanceof String)) {
          return Optional.empty();
        }

        String commandArgs = (String) args.get("command_args");
        String command = "buck " + name + (commandArgs.isEmpty() ? "" : " " + commandArgs);

        return Optional.of(command);
      };

  private final ProjectFilesystem projectFilesystem;

  public ChromeTraceParser(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  /**
   * Parses a Chrome trace and stops parsing once all of the specified matchers have been satisfied.
   * This method parses only one Chrome trace event at a time, which avoids loading the entire trace
   * into memory.
   *
   * @param pathToTrace is a relative path [to the ProjectFilesystem] to a Chrome trace in the "JSON
   *     Array Format."
   * @param chromeTraceEventMatchers set of matchers this invocation of {@code parse()} is trying to
   *     satisfy. Once a matcher finds a match, it will not consider any other events in the trace.
   * @return a {@code Map} where every matcher that found a match will have an entry whose key is
   *     the matcher and whose value is the one returned by {@link ChromeTraceEventMatcher#test(Map,
   *     String)} without the {@link Optional} wrapper.
   */
  public Map<ChromeTraceEventMatcher<?>, Object> parse(
      Path pathToTrace, Set<ChromeTraceEventMatcher<?>> chromeTraceEventMatchers)
      throws IOException {
    Set<ChromeTraceEventMatcher<?>> unmatchedMatchers = new HashSet<>(chromeTraceEventMatchers);
    Preconditions.checkArgument(!unmatchedMatchers.isEmpty(), "Must specify at least one matcher");
    Map<ChromeTraceEventMatcher<?>, Object> results = new HashMap<>();

    try (InputStream input = projectFilesystem.newFileInputStream(pathToTrace);
        MappingIterator<ImmutableMap<String, Object>> it =
            ObjectMappers.READER
                .forType(new TypeReference<ImmutableMap<String, Object>>() {})
                .readValues(input)) {
      featureSearch:
      while (it.hasNext()) {
        // Verify and extract the name property before invoking any of the matchers.
        ImmutableMap<String, Object> event = it.next();
        Object nameEl = event.get("name");
        if (!(nameEl instanceof String)) {
          continue;
        }
        String name = (String) nameEl;

        // Prefer Iterator to Iterable+foreach so we can use remove().
        for (Iterator<ChromeTraceEventMatcher<?>> iter = unmatchedMatchers.iterator();
            iter.hasNext(); ) {
          ChromeTraceEventMatcher<?> chromeTraceEventMatcher = iter.next();
          Optional<?> result = chromeTraceEventMatcher.test(event, name);
          if (result.isPresent()) {
            iter.remove();
            results.put(chromeTraceEventMatcher, result.get());

            if (unmatchedMatchers.isEmpty()) {
              break featureSearch;
            }
          }
        }
      }
    }

    // We could throw if !unmatchedMatchers.isEmpty(), but that might be overbearing.
    return results;
  }

  /**
   * Designed for use with the result of {@link ChromeTraceParser#parse(Path, Set)}. Helper function
   * to avoid some distasteful casting logic.
   */
  @SuppressWarnings("unchecked")
  public static <T> Optional<T> getResultForMatcher(
      ChromeTraceEventMatcher<T> matcher, Map<ChromeTraceEventMatcher<?>, Object> results) {
    T result = (T) results.get(matcher);
    return Optional.ofNullable(result);
  }
}

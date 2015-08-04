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

package com.facebook.buck.util;


import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.timing.Clock;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.io.ByteStreams;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * A ProjectFilesystemWatcher implementation that uses a local watchman service.
 */
public class WatchmanWatcher implements ProjectFilesystemWatcher {

  private static final Logger LOG = Logger.get(WatchmanWatcher.class);
  private static final int DEFAULT_OVERFLOW_THRESHOLD = 10000;
  private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private final EventBus fileChangeEventBus;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final ProcessExecutor processExecutor;
  private final String query;

  /**
   * The maximum number of watchman changes to process in each call to postEvents before
   * giving up and generating an overflow. The goal is to be able to process a reasonable
   * number of human generated changes quickly, but not spend a long time processing lots
   * of changes after a branch switch which will end up invalidating the entire cache
   * anyway. If overflow is negative calls to postEvents will just generate a single
   * overflow event.
   */
  private final int overflow;

  private final long timeoutMillis;

  public WatchmanWatcher(String watchRoot,
                         Optional<String> watchPrefix,
                         EventBus fileChangeEventBus,
                         Clock clock,
                         ObjectMapper objectMapper,
                         ProcessExecutor processExecutor,
                         Iterable<Path> ignorePaths,
                         Iterable<String> ignoreGlobs) {
    this(fileChangeEventBus,
        clock,
        objectMapper,
        processExecutor,
        DEFAULT_OVERFLOW_THRESHOLD,
        DEFAULT_TIMEOUT_MILLIS,
        createQuery(
            objectMapper,
            watchRoot,
            watchPrefix,
            UUID.randomUUID().toString(),
            ignorePaths,
            ignoreGlobs));
  }

  @VisibleForTesting
  WatchmanWatcher(EventBus fileChangeEventBus,
                  Clock clock,
                  ObjectMapper objectMapper,
                  ProcessExecutor processExecutor,
                  int overflow,
                  long timeoutMillis,
                  String query) {
    this.fileChangeEventBus = fileChangeEventBus;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.processExecutor = processExecutor;
    this.overflow = overflow;
    this.timeoutMillis = timeoutMillis;
    this.query = query;
  }

  @VisibleForTesting
  static String createQuery(
      ObjectMapper objectMapper,
      String watchRoot,
      Optional<String> watchPrefix,
      String uuid,
      Iterable<Path> ignorePaths,
      Iterable<String> ignoreGlobs) {
    List<Object> queryParams = new ArrayList<>();
    queryParams.add("query");
    queryParams.add(watchRoot);
    // Note that we use LinkedHashMap so insertion order is preserved. That
    // helps us write tests that don't depend on the undefined order of HashMap.
    Map<String, Object> sinceParams = new LinkedHashMap<>();
    sinceParams.put(
        "since",
        new StringBuilder("n:buckd").append(uuid).toString());

    // Exclude any expressions added to this list.
    List<Object> excludeAnyOf = Lists.<Object>newArrayList("anyof");

    // Exclude all directories.
    excludeAnyOf.add(Lists.newArrayList("type", "d"));

    // Exclude all files under directories in project.ignorePaths.
    //
    // Note that it's OK to exclude .git in a query (event though it's
    // not currently OK to exclude .git in .watchmanconfig). This id
    // because watchman's .git cookie magic is done before the query
    // is applied.
    for (Path ignorePath : ignorePaths) {
      excludeAnyOf.add(
          Lists.newArrayList(
              "match",
              ignorePath.toString() + "/*",
              "wholename"));
    }

    // Exclude all files matching globs in project.ignoreGlobs.
    for (String ignoreGlob : ignoreGlobs) {
      excludeAnyOf.add(
          Lists.newArrayList(
              "match",
              ignoreGlob,
              "wholename"));
    }

    sinceParams.put(
        "expression",
        Lists.newArrayList(
            "not",
            excludeAnyOf));
    sinceParams.put("empty_on_fresh_instance", true);
    sinceParams.put("fields", Lists.newArrayList("name", "exists", "new"));
    if (watchPrefix.isPresent()) {
      sinceParams.put("relative_root", watchPrefix.get());
    }
    queryParams.add(sinceParams);
    try {
      return objectMapper.writeValueAsString(queryParams);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Query Watchman for file change events. If too many events are pending or an error occurs
   * an overflow event is posted to the EventBus signalling that events may have been lost
   * (and so typically caches must be cleared to avoid inconsistency). Interruptions and
   * IOExceptions are propagated to callers, but typically if overflow events are handled
   * conservatively by subscribers then no other remedial action is required.
   */
  @Override
  public void postEvents(BuckEventBus buckEventBus) throws IOException, InterruptedException {
    ProcessExecutor.LaunchedProcess watchmanProcess = processExecutor.launchProcess(
        ProcessExecutorParams.builder()
            .addCommand("watchman", "--server-encoding=json", "--no-pretty", "-j")
            .build());
    try {
      LOG.debug("Writing query to Watchman: %s", query);
      watchmanProcess.getOutputStream().write(query.getBytes(Charsets.US_ASCII));
      watchmanProcess.getOutputStream().close();
      LOG.debug("Parsing JSON output from Watchman");
      final long parseStartTimeMillis = clock.currentTimeMillis();
      InputStream jsonInput = watchmanProcess.getInputStream();
      if (LOG.isVerboseEnabled()) {
        byte[] fullResponse = ByteStreams.toByteArray(jsonInput);
        jsonInput.close();
        jsonInput = new ByteArrayInputStream(fullResponse);
        LOG.verbose("Full JSON: " + new String(fullResponse, Charsets.UTF_8).trim());
      }
      JsonParser jsonParser = objectMapper.getJsonFactory().createJsonParser(jsonInput);
      PathEventBuilder builder = new PathEventBuilder();
      JsonToken token = jsonParser.nextToken();
      /*
       * Watchman returns changes as an array of JSON objects with potentially unstable key
       * ordering:
       * {
       *     "files": [
       *     {
       *         "new": false,
       *         "exists": true,
       *         "name": "bin/buckd",
       *     },
       *     ]
       * }
       * A simple way to parse these changes is to collect the relevant values from each object
       * in a builder and then build an event when the end of a JSON object is reached. When the end
       * of the enclosing JSON object is processed the builder will not contain a complete event, so
       * the object end token will be ignored.
       */
      int eventCount = 0;
      while (token != null) {
        boolean shouldOverflow = false;
        if (eventCount > overflow) {
          LOG.warn(
              "Received too many events from Watchmen (%d > overflow max %d), posting overflow " +
              "event and giving up.",
              eventCount,
              overflow);
          shouldOverflow = true;
        } else {
          long elapsedMillis = clock.currentTimeMillis() - parseStartTimeMillis;
          if (elapsedMillis >= timeoutMillis) {
            LOG.warn(
                "Parsing took too long (timeout %d ms), posting overflow event and giving up.",
                timeoutMillis);
            shouldOverflow = true;
          }
        }

        if (shouldOverflow) {
          postWatchEvent(createOverflowEvent());
          processExecutor.destroyLaunchedProcess(watchmanProcess);
          processExecutor.waitForLaunchedProcess(watchmanProcess);
          return;
        }

        switch (token) {
          case FIELD_NAME:
            String fieldName = jsonParser.getCurrentName();
            switch (fieldName) {
              case "is_fresh_instance":
                // Force caches to be invalidated --- we have no idea what's happening.
                Boolean newInstance = jsonParser.nextBooleanValue();
                if (newInstance) {
                  LOG.info("Fresh watchman instance detected. " +
                          "Posting overflow event to flush caches.");
                  postWatchEvent(createOverflowEvent());
                }
                break;

              case "name":
                builder.setPath(Paths.get(jsonParser.nextTextValue()));
                break;
              case "new":
                if (jsonParser.nextBooleanValue()) {
                  builder.setCreationEvent();
                }
                break;
              case "exists":
                if (!jsonParser.nextBooleanValue()) {
                  builder.setDeletionEvent();
                }
                break;
              case "error":
                WatchmanWatcherException e = new WatchmanWatcherException(
                    jsonParser.nextTextValue());
                LOG.error(
                    e,
                    "Error in Watchman output. Posting an overflow event to flush the caches");
                postWatchEvent(createOverflowEvent());
                throw e;
              case "warning":
                String message = jsonParser.nextTextValue();
                buckEventBus.post(
                    ConsoleEvent.warning("Watchman has produced a warning: %s", message));

                LOG.warn("Watchman has produced a warning! Assuming the worst and posting an " +
                        "overflow event to flush the caches: %s", message);
                postWatchEvent(createOverflowEvent());
                break;
            }
            break;
          case END_OBJECT:
            if (builder.canBuild()) {
              postWatchEvent(builder.build());
              ++eventCount;
            }
            builder = new PathEventBuilder();
            break;
          // $CASES-OMITTED$
          default:
            break;
        }
        token = jsonParser.nextToken();
      }
      int watchmanExitCode;
      LOG.debug("Posted %d Watchman events. Waiting for subprocess to exit...", eventCount);
      watchmanExitCode = processExecutor.waitForLaunchedProcess(watchmanProcess);
      if (watchmanExitCode != 0) {
        LOG.error("Watchman exited with error code %d", watchmanExitCode);
        postWatchEvent(createOverflowEvent()); // Events may have been lost, signal overflow.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteStreams.copy(watchmanProcess.getErrorStream(), buffer);
        throw new WatchmanWatcherException(
            "Watchman failed with exit code " + watchmanExitCode + ": " + buffer.toString());
      } else {
        LOG.debug("Watchman exited cleanly.");
      }
    } catch (InterruptedException e) {
      LOG.warn(e, "Killing Watchman process on interrupted exception");
      postWatchEvent(createOverflowEvent()); // Events may have been lost, signal overflow.
      processExecutor.destroyLaunchedProcess(watchmanProcess);
      processExecutor.waitForLaunchedProcess(watchmanProcess);
      Thread.currentThread().interrupt();
      throw e;
    } catch (IOException e) {
      LOG.error(e, "Killing Watchman process on I/O exception");
      postWatchEvent(createOverflowEvent()); // Events may have been lost, signal overflow.
      processExecutor.destroyLaunchedProcess(watchmanProcess);
      processExecutor.waitForLaunchedProcess(watchmanProcess);
      throw e;
    }
  }

  private void postWatchEvent(WatchEvent<?> event) {
    LOG.verbose("Posting WatchEvent: %s", event);
    fileChangeEventBus.post(event);
  }

  private WatchEvent<Object> createOverflowEvent() {
    return new WatchEvent<Object>() {

      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 1;
      }

      @Override
      @Nullable
      public Object context() {
        return null;
      }

      @Override
      public String toString() {
        return "Watchman Overflow WatchEvent " + kind();
      }
    };
  }

  @Override
  public void close() throws IOException {
  }

  private static class PathEventBuilder {

    private WatchEvent.Kind<Path> kind;
    @Nullable private Path path;

    PathEventBuilder() {
      this.kind = StandardWatchEventKinds.ENTRY_MODIFY;
    }

    public void setCreationEvent() {
      if (kind != StandardWatchEventKinds.ENTRY_DELETE) {
        kind = StandardWatchEventKinds.ENTRY_CREATE;
      }
    }

    public void setDeletionEvent() {
      kind = StandardWatchEventKinds.ENTRY_DELETE;
    }

    public void setPath(Path path) {
      this.path = path;
    }

    public WatchEvent<Path> build() {
      Preconditions.checkNotNull(path);
      return new WatchEvent<Path>() {
        @Override
        public Kind<Path> kind() {
          return kind;
        }

        @Override
        public int count() {
          return 1;
        }

        @Override
        @Nullable
        public Path context() {
          return path;
        }

        @Override
        public String toString() {
          return "Watchman Path WatchEvent " + kind + " " + path;
        }
      };
    }

    public boolean canBuild() {
      return path != null;
    }
  }

  /**
   * @return The version of the Watchman server if available, an absent value otherwise.
   */
  public Optional<String> getWatchmanVersion() throws InterruptedException {
    try {
      Optional<String> result;
      ProcessExecutor.LaunchedProcess watchmanVersionProcess = processExecutor.launchProcess(
          ProcessExecutorParams.builder()
              .addCommand("watchman", "version")
              .build());
      Map<String, String> watchmanVersionOutput = objectMapper.readValue(
          watchmanVersionProcess.getInputStream(),
          new TypeReference<Map<String, String>>() { });
      int exitCode = processExecutor.waitForLaunchedProcess(watchmanVersionProcess);
      if (exitCode != 0) {
        LOG.error("Error %d executing watchman version", exitCode);
        return Optional.absent();
      } else {
        result = Optional.fromNullable(watchmanVersionOutput.get("version"));
        LOG.debug("Watchman version: %s", result);
        return result;
      }
    } catch (IOException e) {
      LOG.error(e, "Could not check if Watchman is available");
      return Optional.absent();
    }
  }
}

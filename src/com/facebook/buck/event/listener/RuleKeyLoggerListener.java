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

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleKeys;
import com.facebook.buck.rules.RuleKey;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

public class RuleKeyLoggerListener implements BuckEventListener {
  private static final Logger LOG = Logger.get(RuleKeyLoggerListener.class);

  private static final String FILE_NAME = "rule_key_logger.tsv";
  private static final int DEFAULT_MIN_LINES_FOR_AUTO_FLUSH = 100;

  private final InvocationInfo info;
  private final Object lock;
  private final ExecutorService outputExecutor;
  private final int minLinesForAutoFlush;
  private final ProjectFilesystem projectFilesystem;

  private List<String> logLines;
  private volatile long logLinesCount;

  public RuleKeyLoggerListener(
      ProjectFilesystem projectFilesystem,
      InvocationInfo info,
      ExecutorService outputExecutor) {
    this(projectFilesystem, info, outputExecutor, DEFAULT_MIN_LINES_FOR_AUTO_FLUSH);
  }

  public RuleKeyLoggerListener(
      ProjectFilesystem projectFilesystem,
      InvocationInfo info,
      ExecutorService outputExecutor,
      int minLinesForAutoFlush) {
    this.logLinesCount = 0;
    this.projectFilesystem = projectFilesystem;
    this.minLinesForAutoFlush = minLinesForAutoFlush;
    this.info = info;
    this.lock = new Object();
    this.outputExecutor = outputExecutor;
    this.logLines = Lists.newArrayList();
  }

  @Subscribe
  public void onArtifactCacheEvent(HttpArtifactCacheEvent.Finished event) {
    if (event.getOperation() != ArtifactCacheEvent.Operation.FETCH ||
        !event.getCacheResult().isPresent()) {
      return;
    }

    CacheResultType cacheResultType = event.getCacheResult().get().getType();
    if (cacheResultType != CacheResultType.MISS && cacheResultType != CacheResultType.ERROR) {
      return;
    }

    List<String> newLogLines = Lists.newArrayList();
    for (RuleKey key : event.getRuleKeys()) {
      newLogLines.add(toTsv(key, cacheResultType));
    }

    synchronized (lock) {
      logLines.addAll(newLogLines);
      logLinesCount = logLines.size();
    }

    flushLogLinesIfNeeded();
  }

  @Subscribe
  public void onBuildRuleEvent(BuildRuleEvent.Finished event) {
    BuildRuleKeys ruleKeys = event.getRuleKeys();
    BuildTarget target = event.getBuildRule().getBuildTarget();
    String ruleKeyLine = toTsv(target, ruleKeys.getRuleKey());
    String inputRuleKeyLine = null;
    if (ruleKeys.getInputRuleKey().isPresent()) {
      inputRuleKeyLine = toTsv(target, ruleKeys.getInputRuleKey().get());
    }

    synchronized (lock) {
      logLines.add(ruleKeyLine);
      if (inputRuleKeyLine != null) {
        logLines.add(inputRuleKeyLine);
      }

      logLinesCount = logLines.size();
    }

    flushLogLinesIfNeeded();
  }

  private static String toTsv(RuleKey key, CacheResultType cacheResultType) {
    return String.format("http\t%s\t%s", key.toString(), cacheResultType.toString());
  }

  private static String toTsv(BuildTarget target, RuleKey ruleKey) {
    return String.format("target\t%s\t%s", target.toString(), ruleKey.toString());
  }

  @Override
  public void outputTrace(BuildId buildId) throws InterruptedException {
    outputExecutor.shutdown();
    try {
      flushLogLines();
    } catch (IOException exception) {
      LOG.error(
          exception,
          "Failed to flush [%d] logLines to file [%s].",
          logLines.size(),
          getLogFilePath().toAbsolutePath());
    }
  }

  public Path getLogFilePath() {
    Path logDir = projectFilesystem.resolve(info.getLogDirectoryPath());
    Path logFile = logDir.resolve(FILE_NAME);
    return logFile;
  }

  private void flushLogLinesIfNeeded() {
    if (logLinesCount > minLinesForAutoFlush) {
      outputExecutor.submit(new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          try {
            flushLogLines();
          } catch (IOException e) {
            LOG.error(e, "Failed to flush logLines from the outputExecutor.");
          }

          return null;
        }
      });
    }
  }

  private void flushLogLines() throws IOException {
    List<String> linesToFlush = logLines;
    synchronized (lock) {
      logLines = Lists.newArrayList();
      logLinesCount = logLines.size();
    }
    if (linesToFlush.isEmpty()) {
      return;
    }

    Path logFile = getLogFilePath();
    projectFilesystem.createParentDirs(logFile);
    try (FileOutputStream stream = new FileOutputStream(logFile.toString(), /* append */ true);
         PrintWriter writer = new PrintWriter(stream)) {
      for (String line : linesToFlush) {
        writer.println(line);
      }
    }
  }
}

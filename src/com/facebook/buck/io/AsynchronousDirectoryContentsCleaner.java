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

package com.facebook.buck.io;

import static com.facebook.buck.util.concurrent.MostExecutors.newSingleThreadExecutor;

import com.facebook.buck.log.Logger;

import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;

import java.nio.file.Path;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.EnumSet;

/**
 * Asynchronously cleans the contents of a directory.
 */
public class AsynchronousDirectoryContentsCleaner {
  private static final Logger LOG = Logger.get(AsynchronousDirectoryContentsCleaner.class);

  private final Path pathToClean;
  private final Executor executor;

  /**
   * A ThreadFactory which ensures the spawned threads do not keep the JVM alive at
   * exit time.
   */
  private static class DaemonThreadFactory implements ThreadFactory {

    private final String threadName;

    public DaemonThreadFactory(String threadName) {
      this.threadName = threadName;
    }

    @Override
    public Thread newThread(Runnable r) {
      Thread newThread = Executors.defaultThreadFactory().newThread(r);
      newThread.setDaemon(true);
      newThread.setName(threadName);
      return newThread;
    }
  }

  public AsynchronousDirectoryContentsCleaner(Path pathToClean) {
    this(
        pathToClean,
        newSingleThreadExecutor(
            new DaemonThreadFactory(
                "AsynchronousDirectoryContentsCleaner-" + pathToClean.toString())));
  }

  @VisibleForTesting
  AsynchronousDirectoryContentsCleaner(Path pathToClean, Executor executor) {
    this.pathToClean = pathToClean;
    this.executor = executor;
  }

  /**
   * Starts cleaning the configured directory in the background.
   *
   * Multiple calls to this method will be serialized, so only one
   * instance of directory cleaning will occur at a time.
   */
  public void startCleaningDirectory() {
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            LOG.debug("Starting to clean %s", pathToClean);
            try {
              MoreFiles.deleteRecursivelyWithOptions(
                  pathToClean,
                  EnumSet.of(MoreFiles.DeleteRecursivelyOptions.DELETE_CONTENTS_ONLY),
                  MoreFiles.ErrorHandler.warn("I/O error cleaning trash"));
            } catch (IOException e) {
              throw new RuntimeException(e);
            } finally {
              LOG.debug("Done cleaning %s", pathToClean);
            }
          }
        });
  }
}

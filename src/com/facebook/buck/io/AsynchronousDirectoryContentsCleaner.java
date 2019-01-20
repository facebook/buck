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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.MostFiles;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Asynchronously cleans the contents of a directory. */
public class AsynchronousDirectoryContentsCleaner {
  private static final Logger LOG = Logger.get(AsynchronousDirectoryContentsCleaner.class);

  private final Executor executor;

  /** A ThreadFactory which ensures the spawned threads do not keep the JVM alive at exit time. */
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

  public AsynchronousDirectoryContentsCleaner() {
    this(newSingleThreadExecutor(new DaemonThreadFactory("AsynchronousDirectoryContentsCleaner")));
  }

  @VisibleForTesting
  AsynchronousDirectoryContentsCleaner(Executor executor) {
    this.executor = executor;
  }

  /**
   * Starts cleaning the configured directory in the background.
   *
   * <p>Multiple calls to this method will be serialized, so only one instance of directory cleaning
   * will occur at a time.
   */
  public void startCleaningDirectory(Path pathToClean) {
    executor.execute(
        () -> {
          LOG.debug("Starting to clean %s", pathToClean);
          try {
            MostFiles.deleteRecursivelyWithOptions(
                pathToClean,
                EnumSet.of(
                    MostFiles.DeleteRecursivelyOptions.IGNORE_NO_SUCH_FILE_EXCEPTION,
                    MostFiles.DeleteRecursivelyOptions.DELETE_CONTENTS_ONLY));
          } catch (IOException e) {
            LOG.warn(e, "I/O error cleaning trash");
          } finally {
            LOG.debug("Done cleaning %s", pathToClean);
          }
        });
  }
}

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

package com.facebook.buck.file.downloader.impl;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.util.exceptions.RetryingException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Downloader} decorator which adds retry logic to any decorated downloader instance.
 *
 * <p>TODO(ttsugrii): support flexible backoff strategy (at least exponential).
 */
public class RetryingDownloader implements Downloader {
  private static final Logger LOG = Logger.get(RetryingDownloader.class);
  private final Downloader decoratedDownloader;
  private final int maxNumberOfRetries;

  private RetryingDownloader(Downloader decoratedDownloader, int maxNumberOfRetries) {
    this.decoratedDownloader = decoratedDownloader;
    this.maxNumberOfRetries = maxNumberOfRetries;
  }

  @Override
  public boolean fetch(BuckEventBus eventBus, URI uri, Path output) throws IOException {
    List<IOException> allExceptions = new ArrayList<>();
    for (int retryCount = 0; retryCount <= maxNumberOfRetries; retryCount++) {
      try {
        return decoratedDownloader.fetch(eventBus, uri, output);
      } catch (IOException exception) {
        LOG.warn(
            exception,
            "Failed to download {0}. {1} retries left",
            uri,
            maxNumberOfRetries - retryCount);
        allExceptions.add(exception);
      }
    }
    throw new RetryingDownloaderException(allExceptions);
  }

  static RetryingDownloader from(Downloader downloader, int maxNumberOfRetries) {
    return new RetryingDownloader(downloader, maxNumberOfRetries);
  }

  public static class RetryingDownloaderException extends RetryingException {

    public RetryingDownloaderException(List<IOException> allExceptions) {
      super(allExceptions);
    }
  }
}

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

package com.facebook.buck.util.network;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.SimpleProcessListener;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.CharBuffer;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Implements a Scribe logger that pipes the data to a local process. This can be more efficient
 * than going over http.
 */
public class LocalProcessScribeLogger extends ScribeLogger {
  private static final Logger LOG = Logger.get(LocalProcessScribeLogger.class);

  private final ScribeLogger fallback;
  private final String command;
  private final ListeningProcessExecutor processExecutor;
  private final ListeningExecutorService executorService;

  public LocalProcessScribeLogger(
      ScribeLogger fallback,
      String command,
      ListeningProcessExecutor processExecutor,
      ListeningExecutorService executorService) {
    this.fallback = fallback;
    this.command = command;
    this.processExecutor = processExecutor;
    this.executorService = executorService;
  }

  @Override
  public ListenableFuture<Void> log(final String category, final Iterable<String> lines) {
    ListenableFuture<Void> localCatSubmitResult =
        executorService.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            sendToProcess(category, lines);
            return null;
          }
        });
    return Futures.catchingAsync(
        localCatSubmitResult,
        Exception.class,
        new AsyncFunction<Throwable, Void>() {
          @Override
          public ListenableFuture<Void> apply(Throwable input) throws Exception {
            LOG.info(input, "Submitting to local process failed, falling back to http.");
            return fallback.log(category, lines);
          }
        },
        executorService);
  }

  private void sendToProcess(String category, Iterable<String> lines) throws Exception {
    ProcessExecutorParams processExecutorParams = ProcessExecutorParams.builder()
        .setCommand(ImmutableList.of(command, category))
        .build();
    SimpleProcessListener listener = new SimpleProcessListener(
        new CharBufferIterator(lines.iterator()),
        Charsets.UTF_8);
    ListeningProcessExecutor.LaunchedProcess process = processExecutor.launchProcess(
        processExecutorParams,
        listener);
    process.wantWrite();

    int exitCode;
    try {
      exitCode = processExecutor.waitForProcess(
          process,
          BlockingHttpEndpoint.DEFAULT_COMMON_TIMEOUT_MS,
          TimeUnit.MILLISECONDS);
    } finally {
      processExecutor.destroyProcess(process, true);
      processExecutor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.DAYS);
    }

    if (exitCode != 0) {
      LOG.warn(
          "Submitting to local process failed. Exit code %s\nstderr:%s\nstdout:%s",
          exitCode,
          listener.getStderr(),
          listener.getStdout());
      throw new Exception("non-zero exit code.");
    }
  }

  @Override
  public void close() throws Exception {
    executorService.shutdown();
    fallback.close();
  }

  private static class CharBufferIterator implements Iterator<CharBuffer> {
    private final Iterator<String> iterator;
    private boolean needsNewLine;

    public CharBufferIterator(Iterator<String> iterator) {
      this.iterator = iterator;
      this.needsNewLine = false;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public CharBuffer next() {
      if (needsNewLine) {
        needsNewLine = false;
        return CharBuffer.wrap("\n");
      }

      needsNewLine = true;
      return CharBuffer.wrap(Preconditions.checkNotNull(iterator.next()));
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}

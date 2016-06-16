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

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.LogviewFormatter;
import com.facebook.buck.util.FakeListeningProcessExecutor;
import com.facebook.buck.util.FakeListeningProcessState;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class LocalProcessScribeLoggerTest {

  @Test
  public void localScript() throws Exception {
    String succeedCommand = "ok_cat";
    String failCommand = "fail_cat";
    String category = "cat_category";

    ProcessExecutorParams failParams = ProcessExecutorParams.ofCommand(failCommand);
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder().putAll(
            ProcessExecutorParams.ofCommand(succeedCommand, category),
            FakeListeningProcessState.ofStdout(""),
            FakeListeningProcessState.ofExpectedStdin("1\n2\n3"),
            FakeListeningProcessState.ofExit(0)
        ).putAll(
            ProcessExecutorParams.ofCommand(failCommand, category),
            FakeListeningProcessState.ofExpectedStdin("fallback"),
            FakeListeningProcessState.ofExit(-1)
        ).build());

    final AtomicReference<String> fallbackContent = new AtomicReference<>();
    ScribeLogger fallback = new ScribeLogger() {
      @Override
      public ListenableFuture<Void> log(final String category, Throwable t) {
        return log(category, LogviewFormatter.format(t));
      }

      @Override
      public ListenableFuture<Void> log(String category, Iterable<String> lines) {
        fallbackContent.set(Joiner.on('\n').join(lines));
        return Futures.immediateFuture(null);
      }

      @Override
      public void close() throws Exception {
      }
    };

    LocalProcessScribeLogger succeedLogger =
        new LocalProcessScribeLogger(
            fallback,
            succeedCommand,
            executor,
            MoreExecutors.newDirectExecutorService());
    succeedLogger.log(category, ImmutableList.of("1", "2", "3")).get();
    assertThat(fallbackContent.get(), Matchers.nullValue());

    LocalProcessScribeLogger failLogger =
        new LocalProcessScribeLogger(
            fallback,
            Joiner.on(' ').join(failParams.getCommand()),
            executor,
            MoreExecutors.newDirectExecutorService());
    failLogger.log(category, ImmutableList.of("fallback")).get();

    assertThat(fallbackContent.get(), Matchers.equalTo("fallback"));

    succeedLogger.close();
    failLogger.close();
  }
}

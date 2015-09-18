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

package com.facebook.buck.util;

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.util.ProcessListeners.CapturingListener;
import com.facebook.buck.util.ProcessListeners.StdinWritingListener;

import com.google.common.collect.ImmutableMultimap;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link FakeListeningProcessExecutor}.
 */
public class FakeListeningProcessExecutorTest {

  @Test
  public void echoTextReceivedOnStdout() throws Exception {
    CapturingListener listener = new CapturingListener();
    ProcessExecutorParams params = ProcessExecutorParams.ofCommand("echo", "Hello");
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder().putAll(
            params,
            FakeListeningProcessState.ofStdout("Hello\n"),
            FakeListeningProcessState.ofExit(0)
        ).build());
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.capturedStdout.toString("UTF-8"), equalTo("Hello\n"));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void catTextSentToStdinReceivedOnStdout() throws Exception {
    ProcessExecutorParams params = ProcessExecutorParams.ofCommand("cat");
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder().putAll(
            params,
            FakeListeningProcessState.ofExpectedStdin("Meow\n"),
            FakeListeningProcessState.ofExpectStdinClosed(),
            FakeListeningProcessState.ofStdout("Meow\n"),
            FakeListeningProcessState.ofExit(0)
        ).build());
    StdinWritingListener listener = new StdinWritingListener("Meow\n");
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    process.wantWrite();
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.capturedStdout.toString("UTF-8"), equalTo("Meow\n"));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void processFailureExitCodeNotZero() throws Exception {
    ProcessExecutorParams params = ProcessExecutorParams.ofCommand("false");
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder().putAll(
            params,
            FakeListeningProcessState.ofExit(1)
        ).build());
    CapturingListener listener = new CapturingListener();
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, not(equalTo(0)));
    assertThat(listener.capturedStdout.toString("UTF-8"), is(emptyString()));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void fakeProcessTimeout() throws Exception {
    ProcessExecutorParams params = ProcessExecutorParams.ofCommand("sleep", "50");
    FakeListeningProcessExecutor executor = new FakeListeningProcessExecutor(
        ImmutableMultimap.<ProcessExecutorParams, FakeListeningProcessState>builder().putAll(
            params,
            FakeListeningProcessState.ofWaitNanos(TimeUnit.SECONDS.toNanos(50)),
            FakeListeningProcessState.ofExit(0)
        ).build());
    CapturingListener listener = new CapturingListener();
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    int returnCode = executor.waitForProcess(process, 100, TimeUnit.MILLISECONDS);
    assertThat(returnCode, equalTo(Integer.MIN_VALUE));
    assertThat(listener.capturedStdout.toString("UTF-8"), is(emptyString()));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }
}

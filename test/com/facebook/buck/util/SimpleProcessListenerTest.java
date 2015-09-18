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

import com.facebook.buck.util.environment.Platform;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link SimpleProcessListener}.
 */
public class SimpleProcessListenerTest {

  @Test
  public void echoTextReceivedOnStdout() throws Exception {
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    SimpleProcessListener listener = new SimpleProcessListener();
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params = ProcessExecutorParams.ofCommand("cmd.exe", "/c", "echo", "Hello");
    } else {
      params = ProcessExecutorParams.ofCommand("echo", "Hello");
    }
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.getStdout(), equalTo(String.format("Hello%n")));
    assertThat(listener.getStderr(), is(emptyString()));
  }

  @Test
  public void catTextSentToStdinReceivedOnStdout() throws Exception {
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params = ProcessExecutorParams.ofCommand(
          "python",
          "-c",
          "import sys, shutil; shutil.copyfileobj(sys.stdin, sys.stdout)");
    } else {
      params = ProcessExecutorParams.ofCommand("cat");
    }
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    SimpleProcessListener listener = new SimpleProcessListener(String.format("Meow%n"));
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    process.wantWrite();
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.getStdout(), equalTo(String.format("Meow%n")));
    assertThat(listener.getStderr(), is(emptyString()));
  }
}

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

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import java.nio.CharBuffer;
import org.junit.Test;

/** Tests for {@link SimpleProcessListener}. */
public class SimpleProcessListenerTest {

  @Test
  public void echoTextReceivedOnStdout() throws Exception {
    ProcessExecutorParams params = ProcessExecutorParams.ofCommand("echo", "Hello");
    FakeListeningProcessExecutor executor =
        new FakeListeningProcessExecutor(
            ImmutableMultimap.of(
                params, FakeListeningProcessState.ofStdout("Hello"),
                params, FakeListeningProcessState.ofExit(0)));
    SimpleProcessListener listener = new SimpleProcessListener();

    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(params, listener);
    int returnCode = executor.waitForProcess(process);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.getStdout(), equalTo("Hello"));
    assertThat(listener.getStderr(), is(emptyString()));
  }

  @Test
  public void catTextSentToStdinReceivedOnStdout() throws Exception {
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params =
          ProcessExecutorParams.ofCommand(
              "python", "-c", "import sys, shutil; shutil.copyfileobj(sys.stdin, sys.stdout)");
    } else {
      params = ProcessExecutorParams.ofCommand("cat");
    }
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    SimpleProcessListener listener = new SimpleProcessListener(String.format("Meow%n"));
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(params, listener);
    process.wantWrite();
    int returnCode = executor.waitForProcess(process);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.getStdout(), equalTo(String.format("Meow%n")));
    assertThat(listener.getStderr(), is(emptyString()));
  }

  @Test
  public void supplierBasedInput() throws Exception {
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params =
          ProcessExecutorParams.ofCommand(
              "python", "-c", "import sys, shutil; shutil.copyfileobj(sys.stdin, sys.stdout)");
    } else {
      params = ProcessExecutorParams.ofCommand("cat");
    }
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    SimpleProcessListener listener =
        new SimpleProcessListener(
            ImmutableList.of(CharBuffer.wrap("Meow"), CharBuffer.wrap("Wow")).iterator(),
            Charsets.UTF_8);
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(params, listener);
    process.wantWrite();
    int returnCode = executor.waitForProcess(process);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.getStdout(), equalTo("MeowWow"));
    assertThat(listener.getStderr(), is(emptyString()));
  }
}

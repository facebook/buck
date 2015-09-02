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

package com.facebook.buck.util.network;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.testutil.TestConsole;

import java.io.IOException;

import org.junit.Test;

public class HostnameFetchingTest {
  @Test
  public void fetchedHostnameMatchesCommandLineHostname() throws IOException, InterruptedException {
    ProcessExecutor executor = new ProcessExecutor(new TestConsole());
    ProcessExecutor.Result result = executor.launchAndExecute(
        ProcessExecutorParams.ofCommand("hostname"));
    assumeThat(
        "hostname returns success",
        result.getExitCode(),
        equalTo(0));
    String expectedHostname = result.getStdout().or("").trim();
    assumeThat(
        "hostname returns non-empty string",
        expectedHostname,
        not(emptyString()));
    assertThat(
        "fetched hostname should equal hostname returned from CLI",
        HostnameFetching.getHostname(),
        equalTo(expectedHostname));
  }
}

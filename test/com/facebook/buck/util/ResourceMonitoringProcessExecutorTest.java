/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util;

import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.ResourceMonitoringProcessExecutor.ResourceMonitoringLaunchedProcess;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class ResourceMonitoringProcessExecutorTest {
  @Test
  public void smokeTest() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), Matchers.equalTo(Platform.LINUX));
    ResourceMonitoringProcessExecutor executor =
        new ResourceMonitoringProcessExecutor(new DefaultProcessExecutor(new TestConsole()));
    ProcessExecutor.LaunchedProcess process =
        executor.launchProcess(ProcessExecutorParams.ofCommand("true"));
    Assert.assertTrue(process instanceof ResourceMonitoringLaunchedProcess);
    ResourceMonitoringLaunchedProcess refined = (ResourceMonitoringLaunchedProcess) process;
    Assert.assertEquals(refined.getCommand(), ImmutableList.of("true"));
    ProcessExecutor.Result result = executor.waitForLaunchedProcess(process);
    Assert.assertEquals(0, result.getExitCode());
  }
}

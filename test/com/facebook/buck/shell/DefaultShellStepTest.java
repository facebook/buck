/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.easymock.EasyMock;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DefaultShellStepTest {

  private static ExecutionContext createEnvContext(
      ImmutableMap<String, String> env) {
    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    expect(context.getEnvironment())
      .andReturn(env)
      .anyTimes();
    replay(context);
    return context;
  }

  @Test
  public void testJavaHomeIsSetIfNotPresent() {
    Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
    ImmutableList<String> args = ImmutableList.<String>of("ARG1", "ARG2");
    DefaultShellStep step = new DefaultShellStep(workingDirectory, args);
    ExecutionContext context = createEnvContext(ImmutableMap.<String, String>of());

    ImmutableMap<String, String> env = step.getEnvironmentVariables(context);

    assertThat(env.get("JAVA_HOME"), is(equalTo(System.getProperty("java.home"))));
  }

  @Test
  public void testJavaHomeIsSetIfPresent() {
    ImmutableMap<String, String> expectedEnv = ImmutableMap.of("JAVA_HOME", "/usr/lib/javawtf");

    Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
    ImmutableList<String> args = ImmutableList.<String>of("ARG1", "ARG2");
    DefaultShellStep step = new DefaultShellStep(workingDirectory, args);
    ExecutionContext context = createEnvContext(expectedEnv);

    ImmutableMap<String, String> env = step.getEnvironmentVariables(context);

    assertThat(env.get("JAVA_HOME"), is(equalTo("/usr/lib/javawtf")));
  }

}


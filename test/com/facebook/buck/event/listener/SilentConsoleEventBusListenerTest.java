/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.event.listener;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.timing.FakeClock;
import com.google.common.collect.ImmutableMap;
import java.util.Locale;
import java.util.Properties;
import org.junit.Test;

public class SilentConsoleEventBusListenerTest {

  @Test
  public void logsDirectErrors() {
    TestConsole testConsole = new TestConsole(Verbosity.SILENT);
    SilentConsoleEventBusListener eventListener =
        new SilentConsoleEventBusListener(
            new RenderingConsole(FakeClock.doNotCare(), testConsole),
            FakeClock.doNotCare(),
            Locale.US,
            new DefaultExecutionEnvironment(ImmutableMap.of(), new Properties()));
    eventListener.printSevereWarningDirectly("message");
    assertThat(testConsole.getTextWrittenToStdErr(), containsString("message"));
  }

  @Test
  public void logsSevereEvents() {
    TestConsole testConsole = new TestConsole(Verbosity.SILENT);
    SilentConsoleEventBusListener eventListener =
        new SilentConsoleEventBusListener(
            new RenderingConsole(FakeClock.doNotCare(), testConsole),
            FakeClock.doNotCare(),
            Locale.US,
            new DefaultExecutionEnvironment(ImmutableMap.of(), new Properties()));
    eventListener.logEvent(ConsoleEvent.severe("message"));
    assertThat(testConsole.getTextWrittenToStdErr(), containsString("message"));
  }

  @Test
  public void doesNotLogWarnings() {
    TestConsole testConsole = new TestConsole(Verbosity.SILENT);
    SilentConsoleEventBusListener eventListener =
        new SilentConsoleEventBusListener(
            new RenderingConsole(FakeClock.doNotCare(), testConsole),
            FakeClock.doNotCare(),
            Locale.US,
            new DefaultExecutionEnvironment(ImmutableMap.of(), new Properties()));
    eventListener.logEvent(ConsoleEvent.warning("message"));
    assertThat(testConsole.getTextWrittenToStdErr(), not(containsString("message")));
  }
}

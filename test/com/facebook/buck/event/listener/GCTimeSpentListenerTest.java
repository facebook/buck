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

package com.facebook.buck.event.listener;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.support.jvm.GCCollectionEvent;
import com.facebook.buck.support.jvm.GCMajorCollectionEvent;
import com.facebook.buck.support.jvm.GCNotificationInfo;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.FakeExecutionEnvironment;
import com.facebook.buck.util.environment.Network;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.Before;
import org.junit.Test;

public class GCTimeSpentListenerTest {
  BuckEventBus buckEventBus;
  FakeBuckEventListener fakeListener;
  GCTimeSpentListener gcTimeSpentListenerInstance;
  GCTimeSpentListenerConfig config;
  ExecutionEnvironment executionEnvironment;

  @Before
  public void setUp() {
    buckEventBus = BuckEventBusForTests.newInstance();
    config =
        FakeBuckConfig.builder()
            .setSections(
                "[gc_time_spent_warning]",
                "excess_time_warning_at_threshold_template = threshold {total_system_memory} {max_jvm_heap}",
                "excess_time_warning_at_end_template = end {total_system_memory} {max_jvm_heap}",
                "threshold_percentage = 0",
                "threshold_in_s = 0")
            .build()
            .getView(GCTimeSpentListenerConfig.class);
    executionEnvironment =
        FakeExecutionEnvironment.of(
            "fake-hostname",
            "fake-username",
            42, // availableCores
            12345L, // totalMemory
            Platform.MACOS,
            new Network(),
            Optional.of("fake-wifi-ssid"),
            ImmutableMap.of("BUCK_TTY", "1"),
            ImmutableSet.of());
    fakeListener = new FakeBuckEventListener();
    gcTimeSpentListenerInstance =
        new GCTimeSpentListener(buckEventBus, config, executionEnvironment);
    buckEventBus.register(gcTimeSpentListenerInstance);
    buckEventBus.register(fakeListener);
    CommandEvent.Started commandStarted =
        CommandEvent.started("build", ImmutableList.of(), Paths.get(""), OptionalLong.of(20), 20L);
    buckEventBus.post(commandStarted);
    buckEventBus.post(
        new GCMajorCollectionEvent(GCNotificationInfo.of(0, 12, new HashMap<>(), new HashMap<>())));
    buckEventBus.post(CommandEvent.finished(commandStarted, ExitCode.SUCCESS));
  }

  @Test
  public void testWarning() {
    assertEquals(6, fakeListener.getEvents().size());
    assertThat(fakeListener.getEvents().get(0), is(instanceOf(CommandEvent.Started.class)));
    assertThat(fakeListener.getEvents().get(1), is(instanceOf(GCCollectionEvent.class)));
    assertThat(fakeListener.getEvents().get(2), is(instanceOf(ConsoleEvent.class)));
    assertThat(
        fakeListener.getEvents().get(3),
        is(instanceOf(GCTimeSpentListener.GCTimeSpentWarningEvent.class)));
    assertThat(fakeListener.getEvents().get(4), is(instanceOf(CommandEvent.Finished.class)));
    assertThat(fakeListener.getEvents().get(5), is(instanceOf(ConsoleEvent.class)));
    ConsoleEvent consoleEventAtThreshold = (ConsoleEvent) fakeListener.getEvents().get(2);
    ConsoleEvent consoleEventAtEnd = (ConsoleEvent) fakeListener.getEvents().get(5);
    assertEquals(
        String.format(
            "threshold %s %s",
            SizeUnit.toHumanReadableString(
                SizeUnit.getHumanReadableSize(
                    executionEnvironment.getTotalMemory(), SizeUnit.BYTES),
                Locale.getDefault()),
            SizeUnit.toHumanReadableString(
                SizeUnit.getHumanReadableSize(Runtime.getRuntime().maxMemory(), SizeUnit.BYTES),
                Locale.getDefault())),
        consoleEventAtThreshold.getMessage());
    assertEquals(
        String.format(
            "end %s %s",
            SizeUnit.toHumanReadableString(
                SizeUnit.getHumanReadableSize(
                    executionEnvironment.getTotalMemory(), SizeUnit.BYTES),
                Locale.getDefault()),
            SizeUnit.toHumanReadableString(
                SizeUnit.getHumanReadableSize(Runtime.getRuntime().maxMemory(), SizeUnit.BYTES),
                Locale.getDefault())),
        consoleEventAtEnd.getMessage());
  }
}

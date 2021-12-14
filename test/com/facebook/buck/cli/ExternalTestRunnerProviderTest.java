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

package com.facebook.buck.cli;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.impl.FakeTestRule;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.test.external.ExternalTestRunnerSelectionEvent;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ExternalTestRunnerProviderTest {

  private BuckConfig createConfig(
      String externalRunner, String allowedPaths, String externalRunnerOverride) {

    RawConfig.Builder projectConfigBuilder = RawConfig.builder();
    if (externalRunner != null) {
      projectConfigBuilder.put("test", "external_runner", externalRunner);
    }
    if (allowedPaths != null) {
      projectConfigBuilder.put("test", "path_prefixes_to_use_external_runner", allowedPaths);
    }

    RawConfig.Builder overridesBuilder = RawConfig.builder();
    if (externalRunnerOverride != null) {
      overridesBuilder.put("test", "external_runner", externalRunnerOverride);
    }

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                projectConfigBuilder.build(),
                filesystem.getRootPath().resolve(".buckconfig").getPath())
            .setOverrides(overridesBuilder.build())
            .build();

    return buckConfig;
  }

  private Iterable<TestRule> createSimpleTestRules(String... targets) {
    return Arrays.stream(targets)
        .map(BuildTargetFactory::newInstance)
        .map(t -> new FakeTestRule(ImmutableSet.of(), t, ImmutableSortedSet.of()))
        .collect(Collectors.toList());
  }

  @Test
  public void testDefaultBehaviourWithNoPathPrefixesDefined() {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    TestEventConsole console = new TestEventConsole();
    ExternalTestRunnerProvider runnerDeterminator =
        new ExternalTestRunnerProvider(eventBus, console);
    Iterable<TestRule> testRules = createSimpleTestRules("//a:a");

    BuckConfig config = createConfig(null, null, "runner");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("runner"))));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());

    config = createConfig("walker", null, "runner");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("runner"))));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());

    config = createConfig("walker", null, null);
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("walker"))));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());

    config = createConfig("walker", null, "");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.empty()));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());

    config = createConfig(null, null, null);
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.empty()));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());
  }

  @Test
  public void testWithTargetsOutOfPathPrefixes() {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    TestEventConsole console = new TestEventConsole();
    ExternalTestRunnerProvider runnerDeterminator =
        new ExternalTestRunnerProvider(eventBus, console);
    Iterable<TestRule> testRules = createSimpleTestRules("//a:a", "//b:b", "//c:c");
    String allowedPaths = "//a,//b";
    String expectedWarning = "You are using a custom External Test Runner";

    BuckConfig config = createConfig(null, allowedPaths, "runner");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("runner"))));
    assertThat(console.getLastPrintedMessage(), Matchers.containsString(expectedWarning));

    config = createConfig("walker", allowedPaths, "runner");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("runner"))));
    assertThat(console.getLastPrintedMessage(), Matchers.containsString(expectedWarning));

    console.clearMessages();
    config = createConfig("walker", allowedPaths, null);
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.empty()));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());

    config = createConfig("walker", allowedPaths, "");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.empty()));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());

    config = createConfig(null, allowedPaths, null);
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.empty()));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());
  }

  @Test
  public void testWithTargetsWithinPathPrefixes() {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    TestEventConsole console = new TestEventConsole();
    ExternalTestRunnerProvider runnerDeterminator =
        new ExternalTestRunnerProvider(eventBus, console);
    Iterable<TestRule> testRules = createSimpleTestRules("//a:a", "//b:b");
    String allowedPaths = "//a,//b";
    String expectedWarning = "Config test.external_runner overridden";

    BuckConfig config = createConfig(null, allowedPaths, "runner");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("runner"))));
    assertThat(console.getLastPrintedMessage(), Matchers.containsString(expectedWarning));

    config = createConfig("walker", allowedPaths, "runner");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("runner"))));
    assertThat(console.getLastPrintedMessage(), Matchers.containsString(expectedWarning));

    config = createConfig("walker", allowedPaths, "");
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.empty()));
    assertThat(console.getLastPrintedMessage(), Matchers.containsString(expectedWarning));

    console.clearMessages();

    config = createConfig("walker", allowedPaths, null);
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.of(ImmutableList.of("walker"))));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());

    config = createConfig(null, allowedPaths, null);
    assertThat(
        runnerDeterminator.getExternalTestRunner(config, testRules),
        Matchers.equalTo(Optional.empty()));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), Matchers.nullValue());
  }

  @Test
  public void testOverrideEventsArePosted() {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    eventBus.register(listener);
    TestEventConsole console = new TestEventConsole();
    ExternalTestRunnerProvider runnerDeterminator =
        new ExternalTestRunnerProvider(eventBus, console);
    String allowedPaths = "//a";

    BuckConfig config = createConfig("runner", allowedPaths, "walker");
    runnerDeterminator.getExternalTestRunner(config, createSimpleTestRules("//a:a"));
    assertThat(
        Iterables.getLast(listener.getExternalRunnerSelectionEvents(), null),
        Matchers.isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));

    config = createConfig(null, allowedPaths, "custom");
    runnerDeterminator.getExternalTestRunner(config, createSimpleTestRules("//c:c"));
    assertThat(listener.getExternalRunnerSelectionEvents(), Matchers.hasSize(2));
    assertThat(
        Iterables.getLast(listener.getExternalRunnerSelectionEvents(), null),
        Matchers.isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void testOverrideEventsAreNotPostedWhenThereIsNoOverride() {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    eventBus.register(listener);
    TestEventConsole console = new TestEventConsole();
    ExternalTestRunnerProvider runnerDeterminator =
        new ExternalTestRunnerProvider(eventBus, console);

    BuckConfig config = createConfig("runner", "//a", null);
    runnerDeterminator.getExternalTestRunner(config, createSimpleTestRules("//a:a"));
    assertThat(listener.getExternalRunnerSelectionEvents(), Matchers.empty());
  }

  @Test
  public void testOverrideEventsAreNotPostedWhenNoPathPrefixesDefined() {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    BuckEventBusForTests.CapturingEventListener listener =
        new BuckEventBusForTests.CapturingEventListener();
    eventBus.register(listener);
    TestEventConsole console = new TestEventConsole();
    ExternalTestRunnerProvider runnerDeterminator =
        new ExternalTestRunnerProvider(eventBus, console);

    BuckConfig config = createConfig("runner", null, "walker");
    runnerDeterminator.getExternalTestRunner(config, createSimpleTestRules("//a:a"));
    assertThat(listener.getExternalRunnerSelectionEvents(), Matchers.empty());
  }
}

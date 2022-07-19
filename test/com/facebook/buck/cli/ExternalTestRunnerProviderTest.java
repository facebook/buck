/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import static com.google.common.collect.Iterables.getLast;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.nullValue;

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
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class ExternalTestRunnerProviderTest {

  private static final String CUSTOM_EXTERNAL_RUNNER_MESSAGE =
      "You are using a custom External Test Runner";
  private static final String EXTERNAL_RUNNER_OVERRIDDEN = "Config test.external_runner overridden";

  private TestEventConsole console;
  private ExternalTestRunnerProvider testRunnerProvider;
  private BuckEventBusForTests.CapturingEventListener buckEventsListener;

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

    return FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(
            projectConfigBuilder.build(), filesystem.getRootPath().resolve(".buckconfig").getPath())
        .setOverrides(overridesBuilder.build())
        .build();
  }

  private Iterable<TestRule> createSimpleTestRulesWithTargets(String... targets) {
    return Arrays.stream(targets)
        .map(BuildTargetFactory::newInstance)
        .map(t -> new FakeTestRule(ImmutableSet.of(), t, ImmutableSortedSet.of()))
        .collect(Collectors.toList());
  }

  @Before
  public void setUp() {
    console = new TestEventConsole();
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    buckEventsListener = new BuckEventBusForTests.CapturingEventListener();
    eventBus.register(buckEventsListener);
    testRunnerProvider = new ExternalTestRunnerProvider(eventBus, console);
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_overrideRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig(null, null, "runner");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_overrideRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, "runner");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_externalRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenEmptyExternalRunnerOverrideAndExternalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_emptyRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_emptyRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig(null, null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig(null, allowedPaths, "runner");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(console.getLastPrintedMessage(), containsString(CUSTOM_EXTERNAL_RUNNER_MESSAGE));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, "runner");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(console.getLastPrintedMessage(), containsString(CUSTOM_EXTERNAL_RUNNER_MESSAGE));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenEmptyExternalRunnerOverrideAndExternalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig(null, allowedPaths, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig(null, allowedPaths, "runner");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_OVERRIDDEN));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("walker", allowedPaths, "runner");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_OVERRIDDEN));
  }

  @Test
  public void
      givenEmptyExternalRunnerOverrideAndExternalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_emptyExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("walker", allowedPaths, "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_OVERRIDDEN));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_externalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("walker", allowedPaths, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig(null, allowedPaths, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_externalTestRunnerSelectionEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("runner", allowedPaths, "walker");

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalTestRunnerSelectionEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//c:c");
    BuckConfig config = createConfig(null, allowedPaths, "custom");

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_selectEventsArePosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("runner", allowedPaths, null);

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.SelectEvent.class));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoAllowedPathsMatch_whenGetExternalRunner_then_noSelectEventsArePosted() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("runner", null, "walker");

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(buckEventsListener.getExternalRunnerSelectionEvents(), empty());
  }
}

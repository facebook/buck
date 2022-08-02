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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class ExternalTestRunnerProviderTest {

  private static final String EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH =
      "You are using a custom External Test Runner";
  private static final String EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH =
      "Config test.external_runner overridden";

  private TestEventConsole console;
  private ExternalTestRunnerProvider testRunnerProvider;
  private BuckEventBusForTests.CapturingEventListener buckEventsListener;

  private BuckConfig createConfig(
      String buckConfigExternalRunner,
      String allowedPaths,
      String cliExternalRunnerOverride,
      String usersLocalBuckConfigRunner) {

    RawConfig.Builder projectConfigBuilder = RawConfig.builder();
    if (buckConfigExternalRunner != null) {
      projectConfigBuilder.put("test", "external_runner", buckConfigExternalRunner);
    }
    if (allowedPaths != null) {
      projectConfigBuilder.put("test", "path_prefixes_to_use_external_runner", allowedPaths);
    }

    RawConfig.Builder overridesBuilder = RawConfig.builder();
    if (cliExternalRunnerOverride != null) {
      overridesBuilder.put("test", "external_runner", cliExternalRunnerOverride);
    }

    RawConfig.Builder localBuilder = RawConfig.builder();
    if (usersLocalBuckConfigRunner != null) {
      localBuilder.put("test", "external_runner", usersLocalBuckConfigRunner);
    }

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    RawConfig projectConfig = projectConfigBuilder.build();
    RawConfig localConfig = localBuilder.build();
    ImmutableMap<Path, RawConfig> configsMap =
        ImmutableMap.of(
            filesystem.getRootPath().resolve(".buckconfig").getPath(), projectConfig,
            filesystem.getRootPath().resolve(".buckconfig.local").getPath(), localConfig);

    RawConfig.Builder builder = RawConfig.builder();
    builder.putAll(projectConfig);
    builder.putAll(localConfig);

    return FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(builder.build(), configsMap)
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
      givenExternalRunnerOverrideAndNoExternalRunnerAndNoLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_overrideRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig(null, null, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_overrideRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_externalRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenEmptyExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_emptyRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, "", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndNoLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig(null, null, null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_overrideRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig(null, null, "runner", "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndEmptyLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_overrideRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, "runner", "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndEmptyLocalRunnerAndNoAllowedPaths_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("walker", null, null, "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndNoLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig(null, allowedPaths, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenEmptyExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, "", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndNoLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig(null, allowedPaths, null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("runner", allowedPaths, "walker", "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("runner", allowedPaths, null, "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("local"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig(null, allowedPaths, null, "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("local"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndNoLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig(null, allowedPaths, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("walker", allowedPaths, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenEmptyExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("walker", allowedPaths, "", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_externalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("walker", allowedPaths, null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndNoLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_noExternalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig(null, allowedPaths, null, null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("runner", allowedPaths, null, "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("local"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig(null, allowedPaths, "walker", "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig(null, allowedPaths, null, "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("local"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("runner", allowedPaths, "walker", "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndEmptyLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("runner", allowedPaths, "walker", "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("walker"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndEmptyLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideExternalRunnerProvidedWithOverrideRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("runner", allowedPaths, null, "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(),
        containsString(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("runner", allowedPaths, "walker", null);

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndNoLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_overrideEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//c:c");
    BuckConfig config = createConfig(null, allowedPaths, "custom", null);

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_selectEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("runner", allowedPaths, null, null);

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.SelectEvent.class));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig(null, allowedPaths, null, "local");

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig(null, allowedPaths, "runner", "local");

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndLocalRunnerAndAllowedPathsMatch_whenGetExternalRunner_then_overrideEventIsPosted() {
    String allowedPaths = "//a";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("runner", allowedPaths, "walker", "runner");

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(
        getLast(buckEventsListener.getExternalRunnerSelectionEvents(), null),
        isA(ExternalTestRunnerSelectionEvent.OverrideEvent.class));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerAndNoAllowedPathsMatch_whenGetExternalRunner_then_noEventsArePosted() {
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a");
    BuckConfig config = createConfig("runner", null, "walker", null);

    testRunnerProvider.getExternalTestRunner(config, testRules);

    assertThat(buckEventsListener.getExternalRunnerSelectionEvents(), empty());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerWithSameNameAndAllowedPathsMatch_whenGetExternalRunner_then_externalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b");
    BuckConfig config = createConfig("runner", allowedPaths, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndNoLocalRunnerWithSameNameAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("runner", allowedPaths, "runner", null);

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndNoExternalRunnerAndLocalRunnerWithSameNameAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig(null, allowedPaths, "runner", "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndLocalRunnerWithSameNameAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalRunnerProvidedWithCustomRunnerMessage() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("runner", allowedPaths, "runner", "local");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenExternalRunnerOverrideAndExternalRunnerAndEmptyLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalRunnerProvidedWithWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, "runner", "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.of(ImmutableList.of("runner"));
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat(
        console.getLastPrintedMessage(), containsString(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH));
  }

  @Test
  public void
      givenNoExternalRunnerOverrideAndExternalRunnerAndEmptyLocalRunnerAndAllowedPathsDontMatch_whenGetExternalRunner_then_externalRunnerProvidedWithNoWarnings() {
    String allowedPaths = "//a,//b";
    Iterable<TestRule> testRules = createSimpleTestRulesWithTargets("//a:a", "//b:b", "//c:c");
    BuckConfig config = createConfig("walker", allowedPaths, null, "");

    Optional<ImmutableList<String>> providedRunner =
        testRunnerProvider.getExternalTestRunner(config, testRules);

    Optional<ImmutableList<String>> expectedRunner = Optional.empty();
    assertThat(providedRunner, equalTo(expectedRunner));
    assertThat("No warnings were printed", console.getLastPrintedMessage(), nullValue());
  }
}

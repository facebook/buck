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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventBusEventConsole;
import com.facebook.buck.event.console.EventConsole;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.test.external.ExternalTestRunnerSelectionEvent;
import com.facebook.buck.util.config.RawConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Collectors;

/** Encapsulates the logic to obtain an External Test Runner from BuckConfig */
public class ExternalTestRunnerProvider {

  private final BuckEventBus eventBus;
  private final EventConsole console;

  protected static final String EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH =
      "\nConfig test.external_runner overridden. You are not using the supported Test Runner for rules under the paths %s."
          + " Please get in touch to discuss your use case (https://fburl.com/tpx-rollout)" // MOE:strip_line
      ;

  protected static final String EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH =
      "\nYou are using a custom External Test Runner for rules within a path that is not supported. Supported paths are %s."
          + " Please get in touch to discuss your use case (https://fburl.com/tpx-rollout)" // MOE:strip_line
      ;

  public ExternalTestRunnerProvider(BuckEventBus eventBus) {
    this(eventBus, new EventBusEventConsole(eventBus));
  }

  public ExternalTestRunnerProvider(BuckEventBus eventBus, EventConsole console) {
    this.console = console;
    this.eventBus = eventBus;
  }

  /**
   * Gets an External Test Runner from `test.external_runner` and
   * `test.path_prefixes_to_use_external_runner` Configs, warning users when an External Test Runner
   * is used on a Path that is not supported.
   *
   * @param buckConfig the configuration to obtain an External Test Runner from
   * @param testRules a list of all test rules that will be executed
   * @return the External Test Runner command or Optional.empty()
   */
  public Optional<ImmutableList<String>> getExternalTestRunner(
      BuckConfig buckConfig, Iterable<TestRule> testRules) {

    Optional<ImmutableList<String>> allowedPathsPrefixes =
        buckConfig.getOptionalListWithoutComments("test", "path_prefixes_to_use_external_runner");
    Optional<ImmutableList<String>> externalTestRunner =
        buckConfig.getView(TestBuckConfig.class).getExternalTestRunner();

    if (allowedPathsPrefixes.isEmpty()) {
      // path_prefixes_to_use_external_runner is empty. So we continue with default behaviour
      return externalTestRunner;
    }

    boolean allTargetsWithinAllowedPrefixes =
        areAllTargetsWithinAllowedPrefixes(testRules, allowedPathsPrefixes.get());
    boolean userOverriddenExternalRunner = hasUserOverriddenExternalRunner(buckConfig);

    if (allTargetsWithinAllowedPrefixes) {
      if (userOverriddenExternalRunner) {
        console.warn(
            String.format(EXTERNAL_RUNNER_OVERRIDDEN_IN_ALLOWED_PATH, allowedPathsPrefixes.get()));
        postOverrideEvent(testRules, allTargetsWithinAllowedPrefixes, externalTestRunner);
      }
      return externalTestRunner;
    } else {
      if (externalTestRunner.isPresent() && userOverriddenExternalRunner) {
        // User has explicitly set an external test runner on a path they that shouldn't be using
        // external runners
        console.warn(
            String.format(EXTERNAL_RUNNER_IN_UNSUPPORTED_PATH, allowedPathsPrefixes.get()));
        postOverrideEvent(testRules, allTargetsWithinAllowedPrefixes, externalTestRunner);
        return externalTestRunner;
      } else {
        // At least one of the targets on a path where external test runners are not supported.
        // As the user did not explicitly set a test runner, the execution will fall back to the
        // internal test runner.
        // Also because the user did not explicitly set a test runner, we are not going to show any
        // warning
        return Optional.empty();
      }
    }
  }

  private boolean areAllTargetsWithinAllowedPrefixes(
      Iterable<TestRule> testRules, ImmutableList<String> allowedPathsPrefixes) {
    return Iterables.all(
        testRules,
        testRule ->
            Iterables.any(
                allowedPathsPrefixes,
                prefix -> testRule.getFullyQualifiedName().startsWith(prefix)));
  }

  /**
   * Check if resolved Config value is the same as defined in the project's .buckConfig file. If
   * they differ, the user has defined an override either in .buckconfig.local or CLI parameters.
   * Config sources with lower precedence than the project's .buckConfig file are ignored.
   *
   * @see <a href="https://buck.build/files-and-dirs/buckconfig.html#config-precedence">Buck Config
   *     precedence</a>
   */
  private boolean hasUserOverriddenExternalRunner(BuckConfig buckConfig) {
    ImmutableMap<Path, RawConfig> configsMap = buckConfig.getConfig().getConfigsMap();
    Path rootBuckConfigPath = buckConfig.getFilesystem().resolve(".buckconfig").getPath();

    Optional<String> projectExternalRunner =
        Optional.ofNullable(configsMap.get(rootBuckConfigPath))
            .flatMap(repoRawConfig -> repoRawConfig.getValue("test", "external_runner"));

    Optional<String> finalValueOfExternalRunner = buckConfig.getValue("test", "external_runner");

    return !projectExternalRunner.equals(finalValueOfExternalRunner);
  }

  private void postOverrideEvent(
      Iterable<TestRule> testRules,
      boolean allTargetsWithinAllowedPrefixes,
      Optional<ImmutableList<String>> customRunner) {
    eventBus.post(
        ExternalTestRunnerSelectionEvent.externalTestRunnerOverridden(
            Iterables.transform(testRules, r -> r.getFullyQualifiedName()),
            allTargetsWithinAllowedPrefixes,
            customRunner.map(r -> r.stream().collect(Collectors.joining(" ")))));
  }
}

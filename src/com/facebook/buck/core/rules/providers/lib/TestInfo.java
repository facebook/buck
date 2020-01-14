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

package com.facebook.buck.core.rules.providers.lib;

import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.rules.providers.impl.BuiltInProviderInfo;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.Optional;
import org.immutables.value.Value;

/** Provider that passes along information needed by the buck test runner / external test runners */
@ImmutableInfo(
    args = {
      "test_name",
      "test_case_name",
      "labels",
      "contacts",
      "timeout_ms",
      "run_tests_separately",
      "type"
    },
    defaultSkylarkValues = {"[]", "[]", "None", "False", "\"custom\""},
    noneable = {"timeout_ms"})
public abstract class TestInfo extends BuiltInProviderInfo<TestInfo> {
  public static final BuiltInProvider<TestInfo> PROVIDER =
      BuiltInProvider.of(ImmutableTestInfo.class);

  // TODO(pjameson): Even more fields like needed_cvoerage

  /** @return the type of test. This should generally be the rule name */
  public abstract String testName();

  /** @return the name of the test case */
  public abstract String testCaseName();

  /** @returns arbitrary string labels for this rule */
  public abstract ImmutableSet<String> labels();

  /** @returns a list of contacts that are responsible for this test */
  public abstract ImmutableSet<String> contacts();

  /** @returns the timeout in milliseconds for this test, or `None` for default limits */
  public abstract Object timeoutMs();

  /** @returns whether this test should be run separately from every other test */
  public abstract boolean runTestsSeparately();

  /** @return the 'type' to pass to the external test runner */
  public abstract String type();

  @Value.Lazy
  public Optional<Long> typedTimeoutMs() {
    Object raw = timeoutMs();
    return raw == Runtime.NONE ? Optional.empty() : Optional.of(((Number) raw).longValue());
  }

  /** Create an instance from native values */
  public static TestInfo of(
      String testName,
      String testCaseName,
      ImmutableSet<String> labels,
      ImmutableSet<String> contacts,
      Optional<Long> timeoutMs,
      boolean runTestsSeparately,
      String type) {
    Object rawTimeoutMs = timeoutMs.isPresent() ? timeoutMs.get() : Runtime.NONE;
    return new ImmutableTestInfo(
        testName, testCaseName, labels, contacts, rawTimeoutMs, runTestsSeparately, type);
  }

  /** Create an instance from skylark objects */
  public static TestInfo instantiateFromSkylark(
      String testName,
      String testCaseName,
      SkylarkList<String> labels,
      SkylarkList<String> contacts,
      Object timeoutMs,
      boolean runTestsSeparately,
      String type,
      Location location)
      throws EvalException {

    return new ImmutableTestInfo(
        testName,
        testCaseName,
        ImmutableSet.copyOf(labels.getContents(String.class, "labels must be a list of strings")),
        ImmutableSet.copyOf(
            contacts.getContents(String.class, "contacts must be a list of strings")),
        BuckSkylarkTypes.validateNoneOrType(location, Number.class, timeoutMs),
        runTestsSeparately,
        type);
  }
}

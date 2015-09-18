/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.immutables.value.Value;

/**
 * A JSON-serializable structure that gets passed to external test runners.
 *
 * NOTE: We're relying on the fact we're using POJOs here and that all the sub-types are
 * JSON-serializable already.  Be careful that we don't break serialization when adding item
 * here.
 */
@Value.Immutable
@BuckStyleImmutable
interface AbstractExternalTestRunnerTestSpec {

  /**
   * @return the build target of this rule.
   */
  String getTarget();

  /**
   * @return a test-specific type string which classifies the test class, so the external runner
   *     knows how to parse the output.
   */
  String getType();

  /**
   * @return the command the external test runner must invoke to run the test.
   */

  ImmutableList<String> getCommand();

  /**
   * @return environment variables the external test runner should provide for the test command.
   */
  ImmutableMap<String, String> getEnv();

  /**
   * @return test labels.
   */
  ImmutableList<Label> getLabels();

  /**
   * @return test contacts.
   */
  ImmutableList<String> getContacts();

}

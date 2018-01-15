/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.step;

/** A collection of common StepExecutionResult constants. */
public class StepExecutionResults {
  // NB: These constants cannot live in AbstractStepExecutionResult, as referencing subclass in
  // static initializer may cause deadlock during classloading.

  public static final StepExecutionResult SUCCESS = StepExecutionResult.of(0);
  public static final StepExecutionResult ERROR = StepExecutionResult.of(1);

  private StepExecutionResults() {} // Utility class. Do not instantiate.
}

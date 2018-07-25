/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit test for {@link com.facebook.buck.step.AbstractExecutionStep}. */
public class AbstractExecutionStepTest {

  @Test
  public void testDescriptionGetters() {
    String description = "How I describe myself.";
    Step step =
        new AbstractExecutionStep(description) {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            return StepExecutionResults.SUCCESS;
          }
        };
    ExecutionContext context = TestExecutionContext.newInstance();

    assertEquals(description, step.getShortName());
    assertEquals(description, step.getDescription(context));
  }
}

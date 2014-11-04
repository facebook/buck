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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import org.easymock.EasyMock;
import org.junit.Test;

/**
 * Unit test for {@link AbstractExecutionStep}.
 */
public class AbstractExecutionStepTest {

  @Test
  public void testDescriptionGetters() {
    String description = "How I describe myself.";
    Step step = new AbstractExecutionStep(description) {
      @Override
      public int execute(ExecutionContext context) {
        return 0;
      }
    };
    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    EasyMock.replay(context);

    assertEquals(description, step.getShortName());
    assertEquals(description, step.getDescription(context));

    EasyMock.verify(context);
  }

}

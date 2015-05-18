/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.google.common.base.Optional;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class ExecutionOrderAwareFakeStepTest {
  @Test
  public void eachStepHasIncrementingExecutionOrder() {
    AtomicInteger order = new AtomicInteger(0);
    ExecutionOrderAwareFakeStep step1 = new ExecutionOrderAwareFakeStep("name", "desc", 0, order);
    ExecutionOrderAwareFakeStep step2 = new ExecutionOrderAwareFakeStep("name", "desc", 0, order);
    ExecutionOrderAwareFakeStep step3 = new ExecutionOrderAwareFakeStep("name", "desc", 0, order);
    ExecutionContext context = TestExecutionContext.newInstance();
    step1.execute(context);
    step2.execute(context);
    step3.execute(context);
    assertThat(step1.getExecutionBeginOrder(), equalTo(Optional.of(0)));
    assertThat(step1.getExecutionEndOrder(), equalTo(Optional.of(1)));
    assertThat(step2.getExecutionBeginOrder(), equalTo(Optional.of(2)));
    assertThat(step2.getExecutionEndOrder(), equalTo(Optional.of(3)));
    assertThat(step3.getExecutionBeginOrder(), equalTo(Optional.of(4)));
    assertThat(step3.getExecutionEndOrder(), equalTo(Optional.of(5)));
  }
}

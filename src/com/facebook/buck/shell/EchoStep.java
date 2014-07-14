/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;

public class EchoStep implements Step {

  private final String message;

  public EchoStep(String message) {
    this.message = Preconditions.checkNotNull(message);
  }

  @Override
  public int execute(ExecutionContext context) {
    context.getBuckEventBus().post(ConsoleEvent.info(message));
    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("echo \"%s\"", message);
  }

  @Override
  public String getShortName() {
    return "echo";
  }
}

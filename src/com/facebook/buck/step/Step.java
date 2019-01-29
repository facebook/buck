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

package com.facebook.buck.step;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import java.io.IOException;

/** Steps are executed in the same working directory as the root cell. */
public interface Step {

  StepExecutionResult execute(ExecutionContext context) throws IOException, InterruptedException;

  /** @return a short name/description for the command, such as "javac". Should fit on one line. */
  String getShortName();

  String getDescription(ExecutionContext context);
}

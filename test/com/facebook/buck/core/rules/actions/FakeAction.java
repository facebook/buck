/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.util.function.TriFunction;
import com.google.common.collect.ImmutableSet;

public class FakeAction extends AbstractAction {

  private final FakeActionExecuteLambda executeFunction;

  public FakeAction(
      ActionRegistry actionRegistry,
      ImmutableSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      FakeActionExecuteLambda executeFunction) {
    super(actionRegistry, inputs, outputs);
    this.executeFunction = executeFunction;
  }

  @Override
  public String getShortName() {
    return "fake-action";
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    return executeFunction.apply(inputs, outputs, executionContext);
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  public FakeActionExecuteLambda getExecuteFunction() {
    return executeFunction;
  }

  @FunctionalInterface
  public interface FakeActionExecuteLambda
      extends TriFunction<
          ImmutableSet<Artifact>,
          ImmutableSet<Artifact>,
          ActionExecutionContext,
          ActionExecutionResult> {}
}

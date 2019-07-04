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
package com.facebook.buck.rules.keys;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.rules.actions.AbstractAction;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/** Just a Fake do nothing Action for rulekey tests */
class SomeAction extends AbstractAction {

  private int i;
  private String a;

  protected SomeAction(ActionRegistry actionRegistry, int i, String a) {
    super(actionRegistry, ImmutableSet.of(), ImmutableSet.of());
    this.i = i;
    this.a = a;
  }

  protected SomeAction(
      ActionRegistry actionRegistry,
      ImmutableSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      int i,
      String a) {
    super(actionRegistry, inputs, outputs);
    this.i = i;
    this.a = a;
  }

  @Override
  public String getShortName() {
    return "some";
  }

  @Override
  public ActionExecutionResult execute(ActionExecutionContext executionContext) {
    return ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}

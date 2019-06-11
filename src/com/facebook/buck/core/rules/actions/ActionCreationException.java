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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.AbstractAction.ActionConstructorParams;
import com.facebook.buck.core.rules.actions.ActionWrapperDataFactory.DeclaredArtifact;
import com.google.common.collect.ImmutableSet;

/** Represents an error during action creation */
public class ActionCreationException extends Exception {

  public ActionCreationException(
      Throwable e,
      Class<? extends Action> clazz,
      BuildTarget target,
      ImmutableSet<Artifact> inputs,
      ImmutableSet<DeclaredArtifact> outputs,
      ActionConstructorParams args) {
    super(
        String.format(
            "Got exception creating action %s of target: %s, inputs: %s, output: %s, args: %s, ",
            clazz, target, inputs, outputs, args),
        e);
  }
}

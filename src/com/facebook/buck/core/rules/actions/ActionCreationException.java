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

import com.facebook.buck.core.exceptions.HumanReadableException;

/** Represents an error during action creation */
public class ActionCreationException extends HumanReadableException {

  public ActionCreationException(Throwable e, Action action) {
    super(
        e,
        "Got exception creating action %s of target: %s, inputs: %s, output: %s.",
        action.getShortName(),
        action.getOwner(),
        action.getInputs(),
        action.getOutputs());
  }

  public ActionCreationException(Action action, String fmt, Object... fmtArgs) {
    super(
        "Error %s when creating action %s of target: %s, inputs: %s, output: %s.",
        String.format(fmt, fmtArgs),
        action.getShortName(),
        action.getOwner(),
        action.getInputs(),
        action.getOutputs());
  }
}

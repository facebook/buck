/*
 * Copyright 2018-present Facebook, Inc.
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

/** The action related information generated from the rule analysis phase, such as a deferred. */
public interface ActionAnalysisData {

  /** @return the identifier for this {@link ActionAnalysisData} */
  Key getKey();

  /** The key used to identify this action. */
  interface Key {}

  /**
   * TODO(bobyf): we probably need to change this to be some other data structure containing the
   * configuration as well
   *
   * @return the owner, which is the the target of the rule that created this action.
   */
  BuildTarget getOwner();
}

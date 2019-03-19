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

/**
 * The action related information generated from the rule analysis phase, such as a deferred.
 *
 * <p>The look up for actions in the rule analysis framework is such that we request for a specific
 * {@link ActionAnalysisData} of a rule via the {@link ActionAnalysisDataKey}.
 */
public interface ActionAnalysisData {

  /**
   * @return the identifier for this {@link ActionAnalysisData}, which points to the corresponding
   *     rule analysis
   */
  ActionAnalysisDataKey getKey();

  /**
   * The ID used to identify this action. This should be unique and stable per instance of {@link
   * ActionAnalysisData}
   */
  interface ID {}
}

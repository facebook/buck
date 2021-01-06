/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.rules.analysis.action;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;

/**
 * The key used to look up the corresponding {@link ActionAnalysisData}.
 *
 * <p>This key has the {@link BuildTarget} to look up the corresponding rule anlaysis computation
 * that produced the desired {@link ActionAnalysisData}. The {@link ActionAnalysisData.ID} is the
 * unique identifier of the desired {@link ActionAnalysisData}, which is used to identify the {@link
 * ActionAnalysisData} of the many that a single rule analysis of a {@link BuildTarget} could
 * produce.
 */
@BuckStylePrehashedValue
public interface ActionAnalysisDataKey {

  /**
   * @return the {@link BuildTarget} for the rule analysis which the corresponding {@link
   *     ActionAnalysisData} is created from
   */
  BuildTarget getBuildTarget();

  /** @return the unique and stable ID of the corresponding {@link ActionAnalysisData} */
  ActionAnalysisData.ID getID();

  static ActionAnalysisDataKey of(BuildTarget buildTarget, ActionAnalysisData.ID iD) {
    return ImmutableActionAnalysisDataKey.of(buildTarget, iD);
  }
}

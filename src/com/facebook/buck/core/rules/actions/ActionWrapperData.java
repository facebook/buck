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

package com.facebook.buck.core.rules.actions;

import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Implementation of {@link ActionAnalysisData} that just holds an {@link Action}. This mimics the
 * effect of having a deferred
 */
@BuckStyleValue
public interface ActionWrapperData extends ActionAnalysisData {

  @Override
  ActionAnalysisDataKey getKey();

  /** @return the {@link Action} this wraps */
  Action getAction();
}

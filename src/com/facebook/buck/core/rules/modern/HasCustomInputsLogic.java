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

package com.facebook.buck.core.rules.modern;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.util.function.ThrowingConsumer;

/**
 * Deriving inputs directly from the @{@link AddToRuleKey} annotated fields of some objects doesn't
 * work correctly or is too slow. When deriving inputs from an object that implements
 * HasCustomInputsLogic, the framework ({@link com.facebook.buck.rules.BuildableSupport}/{@link
 * com.facebook.buck.rules.modern.ModernBuildRule}) will call computeInputs() instead of deriving
 * from @AddToRuleKey fields.
 */
public interface HasCustomInputsLogic {
  <E extends Exception> void computeInputs(ThrowingConsumer<SourcePath, E> consumer) throws E;
}

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

package com.facebook.buck.external;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;

/** Constants used by external steps executing binary. */
public class ExternalBinaryBuckConstants {

  private ExternalBinaryBuckConstants() {}

  /**
   * The path to the root cell associated with the current rule. See {@link
   * IsolatedExecutionContext#getRuleCellRoot()}.
   */
  public static final String ENV_RULE_CELL_ROOT = "BUCK_RULE_CELL_ROOT";
}

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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;

/** Utilities relating to the Cxx diagnostics flavors. */
public class CxxDiagnosticsEnhancer {
  /** Flavor for the individual diagnostic extraction rules of source files. */
  static final InternalFlavor DIAGNOSTIC_EXTRACTION_FLAVOR =
      InternalFlavor.of("diagnostic-extraction");

  /** File name of the aggregation rule output. */
  public static final String DIAGNOSTICS_JSON_FILENAME = "diagnostics.json";

  /** Flavor for the aggregation rule which combines individual source file diagnostics. */
  public static final Flavor DIAGNOSTIC_AGGREGATION_FLAVOR = InternalFlavor.of("diagnostics");
}

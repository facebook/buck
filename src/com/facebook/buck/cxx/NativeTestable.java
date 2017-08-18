/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.model.BuildTarget;

/** Interface marking a rule as having tests. */
public interface NativeTestable {

  /** Return true if this rule is tested by {@code testTarget}, false otherwise. */
  boolean isTestedBy(BuildTarget testTarget);

  /**
   * Return the {@link CxxPreprocessorInput} to expose private headers of this rule. This is used to
   * propagate private headers to the test testing this object. For convenience, tests can see
   * private headers visible in the rule being tested.
   */
  CxxPreprocessorInput getPrivateCxxPreprocessorInput(CxxPlatform cxxPlatform);
}

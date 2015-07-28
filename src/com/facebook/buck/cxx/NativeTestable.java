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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;

/**
 * Interface marking a rule as having tests.
 */
public interface NativeTestable {

  /**
   * Return true if this rule is tested by {@code testTarget}, false
   * otherwise.
   */
  boolean isTestedBy(BuildTarget testTarget);

  /**
   * Return the {@link CxxPreprocessorInput} to expose symbols of
   * this rule.
   *
   * Note: This is duplicated from CxxPreprocessorDep.
   *
   * We need the same information to expose the headers of a target
   * under test to its tests, but any rule that implements
   * CxxPreprocessorDep gets automatically invoked by CxxDescriptionEnhancer
   * to get preprocessor information, which is not what we want.
   */
  CxxPreprocessorInput getCxxPreprocessorInput(
      TargetGraph targetGraph,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility);
}

/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableSet;

/**
 * Provides an ability to inject {@link CxxPreprocessorInput}s for the Swift compilation process.
 * This is useful if {@link AppleLibraryDescription} is composed by another {@link
 * com.facebook.buck.rules.Description}.
 */
public interface AppleLibrarySwiftDelegate {
  ImmutableSet<CxxPreprocessorInput> getPreprocessorInputForSwift(
      BuildTarget buildTarget,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      CxxLibraryDescription.CommonArg args);
}

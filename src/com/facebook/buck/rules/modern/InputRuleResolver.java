/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import java.util.Optional;

public interface InputRuleResolver {
  Optional<BuildRule> resolve(SourcePath path);

  /**
   * Provides access to internal implementation details of the resolver. This should almost never be
   * used.
   */
  UnsafeInternals unsafe();

  /** Encapsulates some exposed internal implementation details. */
  interface UnsafeInternals {
    SourcePathRuleFinder getRuleFinder();
  }
}

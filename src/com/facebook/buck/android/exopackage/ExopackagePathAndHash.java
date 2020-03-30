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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Holds a path to a file and a path to a file containing the hash of the first. */
@BuckStyleValue
public interface ExopackagePathAndHash extends AddsToRuleKey {

  static ExopackagePathAndHash of(SourcePath path, SourcePath hashPath) {
    return ImmutableExopackagePathAndHash.of(path, hashPath);
  }

  @AddToRuleKey
  SourcePath getPath();

  @AddToRuleKey
  SourcePath getHashPath();
}

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

package com.facebook.buck.core.sourcepath;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

/**
 * Simple type representing a {@link com.facebook.buck.core.sourcepath.SourcePath} and a list of
 * file-specific flags.
 */
@BuckStyleValue
public abstract class SourceWithFlags implements Comparable<SourceWithFlags>, AddsToRuleKey {

  @AddToRuleKey
  public abstract SourcePath getSourcePath();

  @AddToRuleKey
  public abstract ImmutableList<String> getFlags();

  @Override
  public int compareTo(SourceWithFlags that) {
    if (this == that) {
      return 0;
    }

    return ComparisonChain.start()
        .compare(this.getSourcePath(), that.getSourcePath())
        .compare(this.getFlags(), that.getFlags(), Ordering.<String>natural().lexicographical())
        .result();
  }

  public static SourceWithFlags of(SourcePath sourcePath) {
    return of(sourcePath, ImmutableList.of());
  }

  public static SourceWithFlags of(SourcePath sourcePath, ImmutableList<String> flags) {
    return ImmutableSourceWithFlags.of(sourcePath, flags);
  }

  public SourceWithFlags withSourcePath(SourcePath sourcePath) {
    return of(sourcePath, getFlags());
  }
}

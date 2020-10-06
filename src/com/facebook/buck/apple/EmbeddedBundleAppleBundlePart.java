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

package com.facebook.buck.apple;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Bundle that is copied as a whole to specific subdirectory in a parent bundle. It's similar to
 * {@code DirectoryContentAppleBundlePart} because embedded bundle should be copied to main bundle
 * as a directory, though it always contains (thus instances of this class are only present when
 * incremental bundling is requested) incremental info {@code SourcePath} which helps to: 1)
 * efficiently copy only changed parts of bundle 2) do not hash embedded bundle contents and do not
 * calculate rulekey for action which hashes that content
 */
@BuckStyleValue
public abstract class EmbeddedBundleAppleBundlePart extends AppleBundlePart
    implements Comparable<EmbeddedBundleAppleBundlePart> {

  @Override
  @AddToRuleKey
  public abstract SourcePath getSourcePath();

  @Override
  @AddToRuleKey
  public abstract AppleBundleDestination getDestination();

  @AddToRuleKey
  public abstract SourcePath getIncrementalInfoSourcePath();

  @AddToRuleKey
  public abstract boolean getCodesignOnCopy();

  public static EmbeddedBundleAppleBundlePart of(
      SourcePath sourcePath,
      AppleBundleDestination destination,
      SourcePath incrementalInfoSourcePath,
      boolean codesignOnCopy) {
    return ImmutableEmbeddedBundleAppleBundlePart.ofImpl(
        sourcePath, destination, incrementalInfoSourcePath, codesignOnCopy);
  }

  @Override
  public int compareTo(EmbeddedBundleAppleBundlePart o) {
    if (getIncrementalInfoSourcePath() != o.getIncrementalInfoSourcePath()) {
      return getIncrementalInfoSourcePath().compareTo(o.getIncrementalInfoSourcePath());
    }
    if (getCodesignOnCopy() != o.getCodesignOnCopy()) {
      return Boolean.compare(getCodesignOnCopy(), o.getCodesignOnCopy());
    }
    return super.compareTo(o);
  }
}

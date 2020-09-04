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
import java.util.Optional;

/** Directory that is copied as a whole to specific subdirectory in bundle. */
@BuckStyleValue
public abstract class DirectoryAppleBundlePart extends AppleBundlePart
    implements Comparable<DirectoryAppleBundlePart> {

  @Override
  @AddToRuleKey
  public abstract SourcePath getSourcePath();

  @Override
  @AddToRuleKey
  public abstract AppleBundleDestination getDestination();

  @Override
  @AddToRuleKey
  public abstract Optional<SourcePath> getContentHashSourcePath();

  @AddToRuleKey
  public abstract boolean getCodesignOnCopy();

  public static DirectoryAppleBundlePart of(
      SourcePath sourcePath, AppleBundleDestination destination) {
    return of(sourcePath, destination, Optional.empty(), false);
  }

  public static DirectoryAppleBundlePart of(
      SourcePath sourcePath,
      AppleBundleDestination destination,
      Optional<SourcePath> maybeContentHashSourcePath,
      boolean codesignOnCopy) {
    return ImmutableDirectoryAppleBundlePart.ofImpl(
        sourcePath, destination, maybeContentHashSourcePath, codesignOnCopy);
  }

  @Override
  public int compareTo(DirectoryAppleBundlePart o) {
    if (getCodesignOnCopy() != o.getCodesignOnCopy()) {
      return Boolean.compare(getCodesignOnCopy(), o.getCodesignOnCopy());
    }
    return super.compareTo(o);
  }
}

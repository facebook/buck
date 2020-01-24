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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.HeaderPathNormalizer.Builder;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** To support private_include_directories and public_include_directories */
@BuckStyleValue
abstract class CxxIncludes extends CxxHeaders {
  @Override
  @AddToRuleKey
  public abstract CxxPreprocessables.IncludeType getIncludeType();

  // TODO(cjhopman): The connection between this path and the corresponding headers and how the
  // rulekey is correctly handled shouldn't be so implicit, they should be next to each other.

  @ExcludeFromRuleKey(
      reason =
          "This includeDir is used for constructing an include arg that likely points into the repo. The corresponding headers are added to rulekeys separately.",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  abstract PathSourcePath getIncludeDir();

  @Nullable
  @Override
  public SourcePath getRoot() {
    return null;
  }

  @Override
  public Optional<SourcePath> getHeaderMap() {
    return Optional.empty();
  }

  @Override
  public void addToHeaderPathNormalizer(Builder builder) {}

  @Override
  public Optional<Path> getResolvedIncludeRoot(SourcePathResolverAdapter resolver) {
    return Optional.of(
        resolver.getAbsolutePath(Preconditions.checkNotNull(getIncludeDir())).normalize());
  }

  @AddToRuleKey
  @Value.Derived
  public String getIncludeDirPath() {
    return getIncludeDir().getRelativePath().toString();
  }
}

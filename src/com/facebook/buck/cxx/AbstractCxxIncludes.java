/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.HeaderPathNormalizer.Builder;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** To support private_include_directories and public_include_directories */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxIncludes extends CxxHeaders {

  @Override
  @Value.Parameter
  @AddToRuleKey
  public abstract CxxPreprocessables.IncludeType getIncludeType();

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
  public Optional<Path> getResolvedIncludeRoot(SourcePathResolver resolver) {
    return Optional.of(
        resolver.getAbsolutePath(Preconditions.checkNotNull(getIncludeDir())).normalize());
  }

  @Value.Parameter
  abstract PathSourcePath getIncludeDir();

  @AddToRuleKey
  @Value.Derived
  public String getIncludeDirPath() {
    return getIncludeDir().getRelativePath().toString();
  }
}

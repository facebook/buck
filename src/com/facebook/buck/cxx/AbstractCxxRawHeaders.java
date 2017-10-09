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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractCxxRawHeaders extends CxxHeaders {

  @Override
  public CxxPreprocessables.IncludeType getIncludeType() {
    return CxxPreprocessables.IncludeType.RAW;
  }

  @Override
  @Nullable
  public SourcePath getRoot() {
    return null;
  }

  @Override
  public Optional<SourcePath> getHeaderMap() {
    return Optional.empty();
  }

  @Override
  @Nullable
  public SourcePath getIncludeRoot() {
    return null;
  }

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    for (SourcePath sourcePath : getHeaders()) {
      builder.addHeader(sourcePath);
    }
  }

  @Override
  public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    // raw headers are used "as are" - no deps
    return Stream.empty();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("type", getIncludeType());
    sink.setReflectively("headers", getHeaders());
  }

  @Value.Parameter
  abstract ImmutableSortedSet<SourcePath> getHeaders();
}

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
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;

/** Wraps a header directory to add to the preprocessors search path. */
@BuckStyleValue
public abstract class CxxHeadersDir extends CxxHeaders {
  // TODO(cjhopman): This should probably be adding more to its rulekey.
  @Override
  @AddToRuleKey
  public abstract CxxPreprocessables.IncludeType getIncludeType();

  @Override
  @AddToRuleKey
  public abstract SourcePath getRoot();

  @Override
  public Optional<SourcePath> getHeaderMap() {
    return Optional.empty();
  }

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    builder.addHeaderDir(getRoot());
  }

  public static CxxHeadersDir of(CxxPreprocessables.IncludeType includeType, SourcePath root) {
    return ImmutableCxxHeadersDir.of(includeType, root);
  }
}

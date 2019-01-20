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

package com.facebook.buck.cxx;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

/** Encapsulates a Swift bridging header */
@Value.Immutable(prehash = true)
@BuckStyleImmutable
abstract class AbstractCxxBridgingHeaders extends CxxHeaders {
  // TODO(cjhopman): This should probably be adding all the other paths to its rulekey.
  @Override
  @Value.Parameter
  @AddToRuleKey
  public abstract CxxPreprocessables.IncludeType getIncludeType();

  @Override
  public SourcePath getRoot() {
    return getBridgingHeader();
  }

  @Override
  public Optional<SourcePath> getHeaderMap() {
    return Optional.empty();
  }

  @Value.Parameter
  @AddToRuleKey
  public abstract SourcePath getBridgingHeader();

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    builder.addBridgingHeader(getBridgingHeader());
  }

  /** @return a {@link CxxHeaders} constructed from the given {@link HeaderSymlinkTree}. */
  public static CxxBridgingHeaders from(SourcePath bridgingHeaderPath) {
    CxxBridgingHeaders.Builder builder = CxxBridgingHeaders.builder();
    builder.setIncludeType(CxxPreprocessables.IncludeType.LOCAL);
    builder.setBridgingHeader(bridgingHeaderPath);
    return builder.build();
  }
}

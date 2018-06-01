/*
 * Copyright 2016-present Facebook, Inc.
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
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStylePackageVisibleImmutable
abstract class AbstractCxxSandboxInclude extends CxxHeaders {

  @Override
  @Value.Parameter
  @AddToRuleKey
  public abstract CxxPreprocessables.IncludeType getIncludeType();

  @Override
  @Value.Parameter
  public abstract SourcePath getRoot();

  @Value.Parameter
  @AddToRuleKey
  public abstract String getIncludeDir();

  @Override
  public Optional<SourcePath> getHeaderMap() {
    return Optional.empty();
  }

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    builder.addHeaderDir(getRoot());
  }

  public static CxxSandboxInclude from(
      SymlinkTree symlinkTree, String includeDir, CxxPreprocessables.IncludeType includeType) {
    CxxSandboxInclude.Builder builder = CxxSandboxInclude.builder();
    builder.setIncludeType(includeType);
    builder.setRoot(symlinkTree.getRootSourcePath(includeDir));
    builder.setIncludeDir(includeDir);
    return builder.build();
  }
}

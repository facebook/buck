/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.NullFileHashCache;

/**
 * Basic implementation of {@link RuleKeyBuilderFactory} that does not inject any contextual
 * information when creating a {@link RuleKey.Builder}.
 * <p>
 * If reading files from disk needs to be faked when computing the hash for a {@link BuildRule} in
 * the resulting {@link RuleKey.Builder}, then a {@link FakeFileHashCache} should be specified in
 * the constructor.
 */
public class FakeRuleKeyBuilderFactory implements RuleKeyBuilderFactory {

  private final FileHashCache fileHashCache;

  public FakeRuleKeyBuilderFactory() {
    this(new NullFileHashCache());
  }

  public FakeRuleKeyBuilderFactory(FileHashCache fileHashCache) {
    this.fileHashCache = fileHashCache;
  }

  @Override
  public Builder newInstance(BuildRule buildRule) {
    return RuleKey.builder(buildRule, fileHashCache);
  }

}

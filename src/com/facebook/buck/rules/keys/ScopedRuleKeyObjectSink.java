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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.RuleKeyObjectSink;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** A wrapper around {@link RuleKeyObjectSink} that respects {@link RuleKeyScopedHasher} scopes. */
public class ScopedRuleKeyObjectSink implements RuleKeyObjectSink {

  private final RuleKeyScopedHasher.ContainerScope scope;
  private final RuleKeyObjectSink delegate;

  public ScopedRuleKeyObjectSink(
      RuleKeyScopedHasher.ContainerScope scope, RuleKeyObjectSink delegate) {
    this.scope = scope;
    this.delegate = delegate;
  }

  @Override
  public RuleKeyObjectSink setReflectively(String key, @Nullable Object val) {
    try (RuleKeyScopedHasher.Scope elementScope = scope.elementScope()) {
      delegate.setReflectively(key, val);
      return this;
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public RuleKeyObjectSink setPath(Path absolutePath, Path ideallyRelative) throws IOException {
    try (RuleKeyScopedHasher.Scope elementScope = scope.elementScope()) {
      delegate.setPath(absolutePath, ideallyRelative);
      return this;
    }
  }
}

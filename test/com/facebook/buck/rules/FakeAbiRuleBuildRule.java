/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.rules.keys.AbiRule;
import com.google.common.base.Strings;

import javax.annotation.Nullable;

public class FakeAbiRuleBuildRule extends FakeBuildRule implements AbiRule {

  @Nullable
  private Sha1HashCode abiKey;

  public FakeAbiRuleBuildRule(BuildRuleParams buildRuleParams, SourcePathResolver resolver) {
    super(buildRuleParams, resolver);
  }

  public FakeAbiRuleBuildRule(String target, SourcePathResolver resolver, BuildRule... deps) {
    super(target, resolver, deps);
  }

  public void setAbiKey(@Nullable Sha1HashCode abiKey) {
    this.abiKey = abiKey;
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    if (abiKey == null) {
      String hashCode = String.valueOf(Math.abs(this.hashCode()));
      abiKey = Sha1HashCode.of(
          Strings.repeat(hashCode, 40 / hashCode.length() + 1).substring(0, 40));
    }
    return abiKey;
  }
}

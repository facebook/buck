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
package com.facebook.buck.apple;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableSortedSet;

public class AppleBundleWithDsym
    extends NoopBuildRule
    implements BuildRuleWithAppleBundle, HasRuntimeDeps {

  private final AppleBundle appleBundle;

  public AppleBundleWithDsym(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      AppleBundle appleBundle) {
    super(buildRuleParams, resolver);
    this.appleBundle = appleBundle;
  }

  @Override
  public AppleBundle getAppleBundle() {
    return appleBundle;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(appleBundle)
        .addAll(getDeclaredDeps())
        .build();
  }
}

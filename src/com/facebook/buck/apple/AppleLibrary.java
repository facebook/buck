/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;

public class AppleLibrary extends AbstractAppleNativeTargetBuildRule {

  private final boolean linkedDynamically;

  public AppleLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      AppleNativeTargetDescriptionArg arg,
      TargetSources targetSources,
      boolean linkedDynamically) {
    super(params, resolver, arg, targetSources);
    this.linkedDynamically = linkedDynamically;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(final RuleKey.Builder builder) {
    return super.appendDetailsToRuleKey(builder)
        .set("linkedDynamically", linkedDynamically);
  }

  public boolean getLinkedDynamically() {
    return linkedDynamically;
  }

  @Override
  protected String getOutputFileNameFormat() {
    return getOutputFileNameFormat(linkedDynamically);
  }

  public static String getOutputFileNameFormat(boolean linkedDynamically) {
    if (linkedDynamically) {
      return "lib%s.dylib";
    } else {
      return "lib%s.a";
    }
  }
}

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

package com.facebook.buck.apple;

import com.facebook.buck.model.BuildTarget;

public class AppleBinaryBuilder
    extends AbstractAppleNativeTargetBuilder<AppleNativeTargetDescriptionArg, AppleBinaryBuilder> {

  @Override
  protected AppleBinaryBuilder getThis() {
    return this;
  }

  protected AppleBinaryBuilder(BuildTarget target) {
    super(FakeAppleRuleDescriptions.BINARY_DESCRIPTION, target);
  }

  public static AppleBinaryBuilder createBuilder(BuildTarget target) {
    return new AppleBinaryBuilder(target);
  }
}

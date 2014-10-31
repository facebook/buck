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
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.Label;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class AppleTestBuilder extends AbstractNodeBuilder<AppleTestDescription.Arg> {

  protected AppleTestBuilder(BuildTarget target) {
    super(new AppleTestDescription(), target);
  }

  public static AppleTestBuilder createBuilder(BuildTarget target) {
    return new AppleTestBuilder(target);
  }

  public AppleTestBuilder setTestBundle(BuildTarget testBundle) {
    arg.testBundle = testBundle;
    return this;
  }

  public AppleTestBuilder setContacts(Optional<ImmutableSortedSet<String>> contacts) {
    arg.contacts = contacts;
    return this;
  }

  public AppleTestBuilder setLabels(Optional<ImmutableSortedSet<Label>> labels) {
    arg.labels = labels;
    return this;
  }

  public AppleTestBuilder setSourceUnderTest(
      Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest) {
    arg.sourceUnderTest = sourceUnderTest;
    return this;
  }

  public AppleTestBuilder setDeps(Optional<ImmutableSortedSet<BuildTarget>> deps) {
    arg.deps = deps;
    return this;
  }

}

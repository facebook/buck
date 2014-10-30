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
import com.google.common.collect.ImmutableSortedSet;

public class XcodeProjectConfigBuilder
    extends AbstractNodeBuilder<XcodeProjectConfigDescription.Arg> {

  protected XcodeProjectConfigBuilder(BuildTarget target) {
    super(new XcodeProjectConfigDescription(), target);
  }

  public static XcodeProjectConfigBuilder createBuilder(BuildTarget target) {
    return new XcodeProjectConfigBuilder(target);
  }

  public XcodeProjectConfigBuilder setProjectName(String projectName) {
    arg.projectName = projectName;
    return this;
  }

  public XcodeProjectConfigBuilder setRules(ImmutableSortedSet<BuildTarget> rules) {
    arg.rules = rules;
    return this;
  }

}

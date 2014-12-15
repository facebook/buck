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

package com.facebook.buck.android;

import static com.facebook.buck.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.google.common.base.Optional;

import java.nio.file.Path;

public class RobolectricTestBuilder
    extends AbstractNodeBuilder<RobolectricTestDescription.Arg> {

  private RobolectricTestBuilder(BuildTarget target) {
    super(
        new RobolectricTestDescription(
            ANDROID_JAVAC_OPTIONS,
            /* testRuleTimeoutMs */ Optional.<Long>absent()),
        target);
  }

  public static RobolectricTestBuilder createBuilder(BuildTarget target) {
    return new RobolectricTestBuilder(target);
  }

  public RobolectricTestBuilder addSrc(Path path) {
    arg.srcs = amend(arg.srcs, new PathSourcePath(path));
    return this;
  }
}

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

package com.facebook.buck.java;

import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC;
import static com.facebook.buck.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

import javax.annotation.Nullable;

public class JavaTestBuilder extends AbstractNodeBuilder<JavaTestDescription.Arg> {
  private JavaTestBuilder(BuildTarget target) {
    super(
        new JavaTestDescription(
            DEFAULT_JAVAC,
            DEFAULT_JAVAC_OPTIONS,
            /* testRuleTimeoutMs */ Optional.<Long>absent()),
        target);
  }

  public static JavaTestBuilder createBuilder(BuildTarget target) {
    return new JavaTestBuilder(target);
  }

  public JavaTestBuilder addDep(BuildTarget rule) {
    arg.deps = amend(arg.deps, rule);
    return this;
  }

  public JavaTestBuilder addSrc(Path path) {
    arg.srcs = amend(arg.srcs, new PathSourcePath(path));
    return this;
  }

  public JavaTestBuilder setSourceUnderTest(
      @Nullable ImmutableSortedSet<BuildTarget> sourceUnderTest) {
    arg.sourceUnderTest = Optional.fromNullable(sourceUnderTest);
    return this;
  }

  public JavaTestBuilder setVmArgs(@Nullable ImmutableList<String> vmArgs) {
    arg.vmArgs = Optional.fromNullable(vmArgs);
    return this;
  }
}

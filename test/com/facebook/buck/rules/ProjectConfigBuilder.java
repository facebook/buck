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
package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import javax.annotation.Nullable;

public class ProjectConfigBuilder extends AbstractNodeBuilder<ProjectConfigDescription.Arg> {

  private ProjectConfigBuilder(BuildTarget target) {
    super(new ProjectConfigDescription(), target);
  }

  public static ProjectConfigBuilder createBuilder(BuildTarget buildTarget) {
    return new ProjectConfigBuilder(buildTarget);
  }

  public ProjectConfigBuilder setSrcRule(@Nullable BuildTarget srcRule) {
    arg.srcTarget = Optional.fromNullable(srcRule);
    return this;
  }

  public ProjectConfigBuilder setSrcRoots(@Nullable ImmutableList<String> srcRoots) {
    arg.srcRoots = Optional.fromNullable(srcRoots);
    return this;
  }

  public ProjectConfigBuilder setSrcResourceRoots(@Nullable ImmutableList<String> srcResourceRoots)
  {
    arg.srcResourceRoots = Optional.fromNullable(srcResourceRoots);
    return this;
  }

  public ProjectConfigBuilder setTestRule(@Nullable BuildTarget testRule) {
    arg.testTarget = Optional.fromNullable(testRule);
    return this;
  }

  public ProjectConfigBuilder setTestRoots(@Nullable ImmutableList<String> testRoots) {
    arg.testRoots = Optional.fromNullable(testRoots);
    return this;
  }

  public ProjectConfigBuilder setTestResourceRoots(@Nullable ImmutableList<String>
      testResourceRoots) {
    arg.testResourceRoots = Optional.fromNullable(testResourceRoots);
    return this;
  }

  public ProjectConfigBuilder setJdkName(@Nullable String jdkName) {
    arg.jdkName = Optional.fromNullable(jdkName);
    return this;
  }

  public ProjectConfigBuilder setJdkType(@Nullable String jdkType) {
    arg.jdkType = Optional.fromNullable(jdkType);
    return this;
  }
}

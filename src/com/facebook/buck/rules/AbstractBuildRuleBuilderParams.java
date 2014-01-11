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

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Functions;

import java.nio.file.Path;

/**
 * Context object that is passed to an {@link AbstractBuildRuleBuilder} constructor.
 */
public interface AbstractBuildRuleBuilderParams {

  /**
   * A function that maps a path relative to the project root to an absolute path.
   * <p>
   * This is likely backed by a {@link ProjectFilesystem}, but we expose only this function, as it
   * makes it easier to create a fake {@link AbstractBuildRuleBuilderParams} for testing that
   * could be implemented by {@link Functions#identity()}.
   */
  public Function<Path, Path> getPathAbsolutifier();

  public RuleKeyBuilderFactory getRuleKeyBuilderFactory();
}

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

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * A {@link SourcePath} that utilizes the output from a {@link BuildRule} as the file it
 * represents.
 */
public class BuildRuleSourcePath extends AbstractSourcePath {

  private final BuildRule rule;
  private final Optional<Path> resolvedPath;

  public BuildRuleSourcePath(BuildRule rule) {
    this(rule, Optional.<Path>absent());
  }

  public BuildRuleSourcePath(BuildRule rule, Path path) {
    this(rule, Optional.of(path));
  }

  private BuildRuleSourcePath(BuildRule rule, Optional<Path> path) {
    this.rule = Preconditions.checkNotNull(rule);
    this.resolvedPath = Preconditions.checkNotNull(path);
  }

  public Optional<Path> getResolvedPath() {
    return resolvedPath;
  }

  @Override
  public BuildRule asReference() {
    return rule;
  }

  public BuildRule getRule() {
    return rule;
  }

}

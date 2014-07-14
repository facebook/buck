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

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * A {@link SourcePath} that utilizes the output from a {@link BuildRule} as the file it
 * represents.
 */
public class BuildRuleSourcePath extends AbstractSourcePath {
  private final BuildRule rule;

  public BuildRuleSourcePath(BuildRule rule) {
    this.rule = Preconditions.checkNotNull(rule);
  }

  @Override
  public Path resolve() {
    Path path = rule.getPathToOutputFile();
    if (path == null) {
      throw new HumanReadableException("No known output for: %s", rule);
    }

    return path;
  }

  @Override
  public BuildRule asReference() {
    return rule;
  }

  public BuildRule getRule() {
    return rule;
  }
}

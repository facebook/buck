/*
 * Copyright 2016-present Facebook, Inc.
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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper for {@link SourcePathResolver} with some helpers for tests..
 */
public final class FakeSourcePathResolver extends SourcePathResolver {
  private Map<BuildTarget, BuildRule> knownRules = new HashMap<>();

  public FakeSourcePathResolver(SourcePathRuleFinder ruleFinder) {
    super(ruleFinder);
  }

  public void addRule(BuildRule rule) {
    knownRules.put(rule.getBuildTarget(), rule);
  }

  @Override
  public Path getAbsolutePath(SourcePath sourcePath) {
    if (sourcePath instanceof BuildTargetSourcePath) {
      BuildRule rule = knownRules.get(((BuildTargetSourcePath) sourcePath).getTarget());
      if (rule != null) {
        return rule.getPathToOutput();
      }
    }

    return super.getAbsolutePath(sourcePath);
  }
}

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

import com.facebook.buck.model.HasOutputName;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * A {@link SourcePath} that utilizes the output from a {@link BuildRule} as the file it
 * represents.
 */
public class BuildRuleSourcePath extends AbstractSourcePath {

  private final BuildRule rule;
  private final String name;
  private final Optional<Path> resolvedPath;

  public BuildRuleSourcePath(BuildRule rule) {
    this(rule, getNameForRule(rule), Optional.<Path>absent());
  }

  public BuildRuleSourcePath(BuildRule rule, String name) {
    this(rule, name, Optional.<Path>absent());
  }

  public BuildRuleSourcePath(BuildRule rule, Path path) {
    this(rule, getNameForRule(rule), Optional.of(path));
  }

  private BuildRuleSourcePath(BuildRule rule, String name, Optional<Path> path) {
    this.rule = Preconditions.checkNotNull(rule);
    this.name = Preconditions.checkNotNull(name);
    this.resolvedPath = Preconditions.checkNotNull(path);
  }

  private static String getNameForRule(BuildRule rule) {

    // If this build rule implements `HasOutputName`, then return the output name
    // it provides.
    if (rule instanceof HasOutputName) {
      HasOutputName hasOutputName = (HasOutputName) rule;
      return hasOutputName.getOutputName();
    }

    // Otherwise, fall back to using the short name of rule's build target.
    return rule.getBuildTarget().getShortNameOnly();
  }

  @Override
  public Path resolve() {
    if (resolvedPath.isPresent()) {
      return resolvedPath.get();
    }

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

  @Override
  public String getName() {
    return name;
  }

  public BuildRule getRule() {
    return rule;
  }

}

/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactDeclarationException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.lib.WriteAction;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;

/**
 * Container for all methods that create actions within the implementation function of a user
 * defined rule
 */
public class SkylarkRuleContextActions implements SkylarkRuleContextActionsApi {

  private final ActionRegistry registry;

  public SkylarkRuleContextActions(ActionRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Artifact declareFile(String path, Location location) throws EvalException {
    try {
      return registry.declareArtifact(Paths.get(path));
    } catch (InvalidPathException e) {
      throw new EvalException(location, String.format("Invalid path '%s' provided", path));
    } catch (ArtifactDeclarationException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }
  }

  @Override
  public void write(Artifact output, String content, boolean isExecutable, Location location)
      throws EvalException {
    try {
      new WriteAction(registry, ImmutableSet.of(), ImmutableSet.of(output), content, isExecutable);
    } catch (HumanReadableException e) {
      throw new EvalException(location, e.getHumanReadableErrorMessage());
    }
  }
}

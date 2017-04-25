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

package com.facebook.buck.rules.macros;

import com.facebook.buck.jvm.java.HasMavenCoordinates;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Resolves to the maven coordinates for a build target referencing a {@link HasMavenCoordinates}.
 */
public class MavenCoordinatesMacroExpander extends BuildTargetMacroExpander<MavenCoordinatesMacro> {

  @Override
  public Class<MavenCoordinatesMacro> getInputClass() {
    return MavenCoordinatesMacro.class;
  }

  protected String getMavenCoordinates(BuildRule rule) throws MacroException {
    if (!(rule instanceof HasMavenCoordinates)) {
      throw new MacroException(
          String.format(
              "%s used in maven macro does not correspond to a rule with maven coordinates",
              rule.getBuildTarget()));
    }
    Optional<String> coordinates = ((HasMavenCoordinates) rule).getMavenCoords();
    if (!coordinates.isPresent()) {
      throw new MacroException(
          String.format(
              "%s used in maven macro does not have maven coordinates", rule.getBuildTarget()));
    }
    return coordinates.get();
  }

  @Override
  protected MavenCoordinatesMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    return MavenCoordinatesMacro.of(parseBuildTarget(target, cellNames, input));
  }

  @Override
  public String expand(SourcePathResolver resolver, BuildRule rule) throws MacroException {
    return getMavenCoordinates(rule);
  }
}

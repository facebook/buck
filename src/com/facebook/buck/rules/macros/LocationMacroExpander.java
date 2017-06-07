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

package com.facebook.buck.rules.macros;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;

/** Expands to the path of a build rules output. */
public class LocationMacroExpander extends BuildTargetMacroExpander<LocationMacro> {

  @Override
  public Class<LocationMacro> getInputClass() {
    return LocationMacro.class;
  }

  @Override
  protected LocationMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    return LocationMacro.of(parseBuildTarget(target, cellNames, input));
  }

  @Override
  public String expand(SourcePathResolver resolver, BuildRule rule) throws MacroException {
    SourcePath output = rule.getSourcePathToOutput();
    if (output == null) {
      throw new MacroException(
          String.format(
              "%s used in location macro does not produce output", rule.getBuildTarget()));
    }
    return resolver.getAbsolutePath(output).toString();
  }
}

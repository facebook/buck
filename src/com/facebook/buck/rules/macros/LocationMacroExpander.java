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

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.ProjectFilesystem;

import java.nio.file.Path;

/**
 * Expands to the path of a build rules output.
 */
public class LocationMacroExpander extends BuildTargetMacroExpander {

  public LocationMacroExpander(BuildTargetParser parser) {
    super(parser);
  }

  @Override
  public String expand(ProjectFilesystem filesystem, BuildRule rule) throws MacroException {
    Path output = rule.getPathToOutputFile();
    if (output == null) {
      throw new MacroException(
          String.format(
              "%s used in location macro does not produce output",
              rule.getBuildTarget()));
    }
    return filesystem.resolve(output).toString();
  }

}

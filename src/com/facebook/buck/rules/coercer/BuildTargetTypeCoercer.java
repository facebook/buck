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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

public class BuildTargetTypeCoercer extends LeafTypeCoercer<BuildTarget> {

  private final BuildTargetParser buildTargetParser;

  public BuildTargetTypeCoercer(BuildTargetParser buildTargetParser) {
    this.buildTargetParser = Preconditions.checkNotNull(buildTargetParser);
  }

  @Override
  public Class<BuildTarget> getOutputClass() {
    return BuildTarget.class;
  }

  @Override
  public BuildTarget coerce(
      BuildRuleResolver unused,
      ProjectFilesystem alsoUnused,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof BuildTarget) {
      return (BuildTarget) object;
    }

    if (object instanceof String) {
      String param = (String) object;
      try {
        return buildTargetParser.parse(
            param,
            ParseContext.forBaseName("//" + pathRelativeToProjectRoot));
      } catch (BuildTargetParseException e) {
        throw CoerceFailedException.simple(object, getOutputClass());
      }
    }

    throw CoerceFailedException.simple(object, getOutputClass());
  }
}

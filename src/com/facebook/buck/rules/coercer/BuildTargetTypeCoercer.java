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
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;

import java.nio.file.Path;

public class BuildTargetTypeCoercer extends LeafTypeCoercer<BuildTarget> {

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
      if (param.startsWith(BuildTarget.BUILD_TARGET_PREFIX) || param.charAt(0) == ':') {
        int colon = param.indexOf(':');
        if (colon == 0 && param.length() > 1) {
          return new BuildTarget(
              BuildTarget.BUILD_TARGET_PREFIX + pathRelativeToProjectRoot.toString(),
              param.substring(1));
        } else if (colon > 0 && param.length() > 2) {
          return new BuildTarget(param.substring(0, colon), param.substring(colon + 1));
        }
      }
    }

    throw CoerceFailedException.simple(pathRelativeToProjectRoot, object, getOutputClass());
  }
}

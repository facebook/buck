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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.BuildTargetParser;

import java.nio.file.Path;

public class PathTypeCoercer extends LeafTypeCoercer<Path> {

  @Override
  public Class<Path> getOutputClass() {
    return Path.class;
  }

  @Override
  public Path coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof String) {
      String path = (String) object;

      if (path.isEmpty()) {
        throw new CoerceFailedException("invalid path");
      }
      final Path normalizedPath = pathRelativeToProjectRoot.resolve(path).normalize();

      // Verify that the path exists
      try {
        filesystem.getPathForRelativeExistingPath(normalizedPath);
      } catch (RuntimeException e) {
        throw new CoerceFailedException(
            String.format("no such file or directory '%s'", normalizedPath),
            e);
      }

      return normalizedPath;
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }
}

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
package com.facebook.buck.jvm.common;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;

public final class ResourceValidator {
  public static ImmutableSortedSet<SourcePath> validateResources(
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<SourcePath> resourcePaths) {
    for (Path path : resolver.filterInputsToCompareToOutput(resourcePaths)) {
      if (!filesystem.exists(path)) {
        throw new HumanReadableException("Error: `resources` argument '%s' does not exist.", path);
      } else if (filesystem.isDirectory(path)) {
        throw new HumanReadableException(
            "Error: a directory is not a valid input to the `resources` argument: %s", path);
      }
    }
    return resourcePaths;
  }

  private ResourceValidator() {}
}

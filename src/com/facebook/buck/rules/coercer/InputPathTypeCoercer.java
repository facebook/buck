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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.modern.InputPath;
import java.nio.file.Path;

public class InputPathTypeCoercer extends LeafTypeCoercer<InputPath> {

  private final TypeCoercer<SourcePath> pathTypeCoercer;

  public InputPathTypeCoercer(TypeCoercer<SourcePath> pathTypeCoercer) {
    this.pathTypeCoercer = pathTypeCoercer;
  }

  @Override
  public Class<InputPath> getOutputClass() {
    return InputPath.class;
  }

  @Override
  public InputPath coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    SourcePath path =
        pathTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, object);
    return new InputPath(path);
  }
}

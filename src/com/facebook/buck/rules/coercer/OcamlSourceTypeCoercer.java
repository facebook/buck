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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import java.nio.file.Path;

/** A type coercer to handle source entries in OCaml rules. */
public class OcamlSourceTypeCoercer implements TypeCoercer<OcamlSource> {
  private final TypeCoercer<SourcePath> sourcePathTypeCoercer;

  OcamlSourceTypeCoercer(TypeCoercer<SourcePath> sourcePathTypeCoercer) {
    this.sourcePathTypeCoercer = sourcePathTypeCoercer;
  }

  @Override
  public Class<OcamlSource> getOutputClass() {
    return OcamlSource.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return sourcePathTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, OcamlSource object, Traversal traversal) {
    sourcePathTypeCoercer.traverse(cellRoots, object.getSource(), traversal);
  }

  @Override
  public OcamlSource coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof OcamlSource) {
      return (OcamlSource) object;
    }

    if (object instanceof String) {
      String name = (String) object;
      return OcamlSource.ofNameAndSourcePath(
          name,
          sourcePathTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, object));
    }

    throw CoerceFailedException.simple(object, getOutputClass());
  }
}

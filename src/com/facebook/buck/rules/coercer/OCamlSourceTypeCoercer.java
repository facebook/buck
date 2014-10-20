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

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

/**
 * A type coercer to handle source entries in OCaml rules.
 */
public class OCamlSourceTypeCoercer implements TypeCoercer<OCamlSource> {
  private final TypeCoercer<SourcePath> sourcePathTypeCoercer;

  OCamlSourceTypeCoercer(
      TypeCoercer<SourcePath> sourcePathTypeCoercer) {
    this.sourcePathTypeCoercer = Preconditions.checkNotNull(sourcePathTypeCoercer);
  }

  @Override
  public Class<OCamlSource> getOutputClass() {
    return OCamlSource.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return sourcePathTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(OCamlSource object, Traversal traversal) {
    sourcePathTypeCoercer.traverse(object.getSource(), traversal);
  }

  @Override
  public Optional<OCamlSource> getOptionalValue() {
    return Optional.absent();
  }

  @Override
  public OCamlSource coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof OCamlSource) {
      return (OCamlSource) object;
    }

    if (object instanceof String) {
      String name = (String) object;
      return OCamlSource.ofNameAndSourcePath(
          name, sourcePathTypeCoercer.coerce(
              buildTargetParser,
              filesystem,
              pathRelativeToProjectRoot,
              object));
    }

    throw CoerceFailedException.simple(object, getOutputClass());
  }
}

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
import com.facebook.buck.parser.BuildTargetParser;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.List;

/**
 * A type coercer to handle destination references Apple bundle rules.
 */
public class AppleBundleDestinationTypeCoercer implements TypeCoercer<AppleBundleDestination> {
  private final EnumTypeCoercer<AppleBundleDestination.SubfolderSpec> enumTypeCoercer;
  private final TypeCoercer<String> stringTypeCoercer;

  public AppleBundleDestinationTypeCoercer(TypeCoercer<String> stringTypeCoercer) {
    enumTypeCoercer = new EnumTypeCoercer<>(AppleBundleDestination.SubfolderSpec.class);
    this.stringTypeCoercer = stringTypeCoercer;
  }

  @Override
  public Class<AppleBundleDestination> getOutputClass() {
    return AppleBundleDestination.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return enumTypeCoercer.hasElementClass(types) || stringTypeCoercer.hasElementClass(types);
  }

  @Override
  public Optional<AppleBundleDestination> getOptionalValue() {
    return Optional.<AppleBundleDestination>of(
        ImmutableAppleBundleDestination.of(
            AppleBundleDestination.SubfolderSpec.RESOURCES,
            Optional.<String>absent()));
  }

  @Override
  public void traverse(AppleBundleDestination object, Traversal traversal) {
    enumTypeCoercer.traverse(object.getSubfolderSpec(), traversal);
    if (object.getSubpath().isPresent()) {
      stringTypeCoercer.traverse(object.getSubpath().get(), traversal);
    }
  }

  @Override
  public AppleBundleDestination coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (object instanceof AppleBundleDestination) {
      return (AppleBundleDestination) object;
    }

    if (object instanceof String) {
      AppleBundleDestination.SubfolderSpec subfolderSpec = enumTypeCoercer.coerce(
          buildTargetParser,
          filesystem,
          pathRelativeToProjectRoot,
          object);
      return ImmutableAppleBundleDestination.of(subfolderSpec, Optional.<String>absent());
    }

    if (object instanceof List<?>) {
      List<?> list = (List<?>) object;
      Object first = list.size() >= 1 ? list.get(0) : null;
      Object second = list.size() >= 2 ? list.get(1) : null;

      if (first instanceof String && second instanceof String) {
        AppleBundleDestination.SubfolderSpec subfolderSpec = enumTypeCoercer.coerce(
            buildTargetParser,
            filesystem,
            pathRelativeToProjectRoot,
            first);
        String subpath = stringTypeCoercer.coerce(
            buildTargetParser,
            filesystem,
            pathRelativeToProjectRoot,
            second);
        return ImmutableAppleBundleDestination.of(subfolderSpec, Optional.of(subpath));
      }
    }

    throw CoerceFailedException.simple(object, getOutputClass());
  }
}

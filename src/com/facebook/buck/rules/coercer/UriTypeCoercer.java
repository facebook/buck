/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.reflect.TypeToken;
import java.net.URI;
import java.net.URISyntaxException;

/** Coerce to {@link java.net.URI}. */
class UriTypeCoercer extends LeafUnconfiguredOnlyCoercer<URI> {

  @Override
  public TypeToken<URI> getUnconfiguredType() {
    return TypeToken.of(URI.class);
  }

  @Override
  public URI coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {

    if (object instanceof URI) {
      return (URI) object;
    }

    if (object instanceof String) {
      try {
        return new URI((String) object);
      } catch (URISyntaxException e) {
        throw CoerceFailedException.simple(
            object, getOutputType(), "Cannot covert to a URI: " + object);
      }
    }

    throw CoerceFailedException.simple(object, getOutputType());
  }
}

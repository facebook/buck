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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

public class UriTypeCoercer extends LeafTypeCoercer<URI> {

  @Override
  public Class<URI> getOutputClass() {
    return URI.class;
  }

  @Override
  public URI coerce(
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {

    if (object instanceof URI) {
      return (URI) object;
    }

    if (object instanceof String) {
      try {
        return new URI((String) object);
      } catch (URISyntaxException e) {
        throw CoerceFailedException.simple(
            object,
            getOutputClass(),
            "Cannot covert to a URI: " + object);
      }
    }

    throw CoerceFailedException.simple(object, getOutputClass());
  }
}

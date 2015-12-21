/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.PackagedResource;

import java.nio.file.Path;

/**
 * {@link SourcePath} that wraps a resource embedded inside a JAR.
 */
public class ResourceSourcePath extends AbstractSourcePath<ResourceSourcePath> {
  private final PackagedResource resource;

  public ResourceSourcePath(PackagedResource resource) {
    this.resource = resource;
  }

  /**
   * Class name followed by relative file path, e.g.:
   * com.facebook.buck.MyClass#some_resource_file.abc
   */
  public String getResourceIdentifier() {
    return resource.getResourceIdentifier();
  }

  public Path getAbsolutePath() {
    return resource.get();
  }

  @Override
  protected Object asReference() {
    return getResourceIdentifier();
  }

  @Override
  protected int compareReferences(ResourceSourcePath o) {
    if (o == this) {
      return 0;
    }

    return getResourceIdentifier().compareTo(o.getResourceIdentifier());
  }
}

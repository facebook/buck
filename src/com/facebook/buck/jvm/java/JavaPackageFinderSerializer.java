/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class JavaPackageFinderSerializer {
  private JavaPackageFinderSerializer() {}

  private static final String TYPE = "type";
  private static final String TYPE_DEFAULT = "type_default";
  private static final String PATHS_FROM_ROOT = "paths_from_root";
  private static final String PATH_ELEMENTS = "path_elements";

  private static final String TYPE_RESOURCE_ROOT = "type_resource_root";
  private static final String RESOURCES_ROOT = "resources_root";
  private static final String FALLBACK_FINDER = "fallback_finder";

  public static ImmutableMap<String, Object> serialize(JavaPackageFinder packageFinder) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

    if (packageFinder instanceof DefaultJavaPackageFinder) {
      builder.put(TYPE, TYPE_DEFAULT);
      builder.put(
          PATH_ELEMENTS, ((DefaultJavaPackageFinder) packageFinder).getPathElements().asList());
      builder.put(
          PATHS_FROM_ROOT, ((DefaultJavaPackageFinder) packageFinder).getPathsFromRoot().asList());
    } else if (packageFinder instanceof ResourcesRootPackageFinder) {
      builder.put(TYPE, TYPE_RESOURCE_ROOT);
      builder.put(
          RESOURCES_ROOT,
          ((ResourcesRootPackageFinder) packageFinder).getResourcesRoot().toString());
      builder.put(
          FALLBACK_FINDER,
          serialize(((ResourcesRootPackageFinder) packageFinder).getFallbackFinder()));
    } else {
      throw new RuntimeException(
          String.format(
              "Cannot serialize JavaPackageFinder with class: %s", packageFinder.getClass()));
    }

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public static JavaPackageFinder deserialize(Map<String, Object> data) {
    String type = (String) data.get(TYPE);
    Preconditions.checkNotNull(type);
    Preconditions.checkArgument(
        type.equals(TYPE_DEFAULT) || type.equals(TYPE_RESOURCE_ROOT), "Unknown type: %s", type);

    if (type.equals(TYPE_DEFAULT)) {
      Preconditions.checkArgument(data.containsKey(PATH_ELEMENTS));
      ImmutableSet<String> pathElements =
          ImmutableSet.copyOf((List<String>) data.get(PATH_ELEMENTS));

      Preconditions.checkArgument(data.containsKey(PATHS_FROM_ROOT));
      ImmutableSortedSet<String> pathsFromRoot =
          ImmutableSortedSet.copyOf((List<String>) data.get(PATHS_FROM_ROOT));

      return new DefaultJavaPackageFinder(pathsFromRoot, pathElements);
    } else if (type.equals(TYPE_RESOURCE_ROOT)) {
      Path resourcesRoot = Paths.get((String) data.get(RESOURCES_ROOT));
      Map<String, Object> javaPackageFinderData = (Map<String, Object>) data.get(FALLBACK_FINDER);
      Preconditions.checkNotNull(javaPackageFinderData);
      JavaPackageFinder fallbackFinder = deserialize(javaPackageFinderData);
      return new ResourcesRootPackageFinder(resourcesRoot, fallbackFinder);
    }

    throw new RuntimeException(
        String.format("Unable to deserialize JavaPackageFinder from data: %s", data));
  }
}

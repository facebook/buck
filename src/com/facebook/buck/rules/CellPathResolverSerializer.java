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

package com.facebook.buck.rules;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class CellPathResolverSerializer {

  private static final String TYPE = "type";
  private static final String TYPE_DEFAULT = "default";
  private static final String ROOT_PATH = "root_path";
  private static final String CELL_PATHS = "cell_paths";

  private CellPathResolverSerializer() {}

  public static ImmutableMap<String, Object> serialize(CellPathResolver resolver) {
    Preconditions.checkArgument(
        resolver instanceof DefaultCellPathResolver,
        "Unsupported CellPathResolver class: %s",
        resolver.getClass());

    DefaultCellPathResolver defaultResolver = (DefaultCellPathResolver) resolver;

    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.put(TYPE, TYPE_DEFAULT);
    builder.put(ROOT_PATH, defaultResolver.getRoot().toString());

    ImmutableMap.Builder<String, String> cellPathAsStrings = ImmutableMap.builder();
    for (Map.Entry<String, Path> entry : defaultResolver.getCellPaths().entrySet()) {
      cellPathAsStrings.put(entry.getKey(), entry.getValue().toString());
    }
    builder.put(CELL_PATHS, cellPathAsStrings.build());
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public static CellPathResolver deserialize(Map<String, Object> data) {
    String type =
        (String)
            Preconditions.checkNotNull(data.get(TYPE), "Expected to have value for key %s", TYPE);
    Preconditions.checkArgument(
        type.equals(TYPE_DEFAULT),
        "Only CellPathResolver with type '%s' are supported, but found '%s' type",
        TYPE_DEFAULT,
        type);

    String rootPathAsString =
        (String)
            Preconditions.checkNotNull(
                data.get(ROOT_PATH), "Expected to have value for key %s", ROOT_PATH);
    Path rootPath = Paths.get(rootPathAsString);

    Map<String, String> cellPathsAsStrings =
        (Map<String, String>)
            Preconditions.checkNotNull(
                data.get(CELL_PATHS), "Expected to have value for key %s", CELL_PATHS);

    ImmutableMap.Builder<String, Path> cellPaths = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : cellPathsAsStrings.entrySet()) {
      cellPaths.put(entry.getKey(), Paths.get(entry.getValue()));
    }

    return new DefaultCellPathResolver(rootPath, cellPaths.build());
  }
}

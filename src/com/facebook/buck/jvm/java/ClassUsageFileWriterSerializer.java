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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ClassUsageFileWriterSerializer {
  private ClassUsageFileWriterSerializer() {}

  private static final String TYPE = "type";
  private static final String TYPE_DEFAULT = "type_default";
  private static final String TYPE_NOOP = "type_noop";
  private static final String RELATIVE_PATH = "relative_path";

  public static ImmutableMap<String, Object> serialize(ClassUsageFileWriter writer) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

    if (writer instanceof DefaultClassUsageFileWriter) {
      builder.put(TYPE, TYPE_DEFAULT);
      builder.put(
          RELATIVE_PATH, ((DefaultClassUsageFileWriter) writer).getRelativePath().toString());
    } else if (writer instanceof NoOpClassUsageFileWriter) {
      builder.put(TYPE, TYPE_NOOP);
    } else {
      throw new UnsupportedOperationException(
          String.format("Cannot serialize ClassUsageFileWriter with class: %s", writer.getClass()));
    }

    return builder.build();
  }

  public static ClassUsageFileWriter deserialize(Map<String, Object> data) {
    String type = (String) data.get(TYPE);
    Preconditions.checkNotNull(type);
    if (type.equals(TYPE_DEFAULT)) {
      Preconditions.checkArgument(data.containsKey(RELATIVE_PATH));
      Path relativePath = Paths.get((String) data.get(RELATIVE_PATH));
      return new DefaultClassUsageFileWriter(relativePath);
    } else if (type.equals(TYPE_NOOP)) {
      return NoOpClassUsageFileWriter.instance();
    } else {
      throw new RuntimeException(
          String.format("Cannot deserialize ClassUsageFileWriter with type: %s", type));
    }
  }
}

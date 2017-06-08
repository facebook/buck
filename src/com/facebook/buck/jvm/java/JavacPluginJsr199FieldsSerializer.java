/*
 * Copyright 2017-present Facebook, Inc.
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
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JavacPluginJsr199FieldsSerializer {

  private static final String CAN_REUSE_CLASSPATH = "can_reuse_classpath";
  private static final String PROCESSOR_NAMES = "processor_names";
  private static final String CLASSPATH = "classpath";

  private JavacPluginJsr199FieldsSerializer() {}

  public static ImmutableMap<String, Object> serialize(JavacPluginJsr199Fields fields) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

    builder.put(CAN_REUSE_CLASSPATH, fields.getCanReuseClassLoader());
    builder.put(PROCESSOR_NAMES, fields.getProcessorNames().asList());
    builder.put(
        CLASSPATH, fields.getClasspath().stream().map(URL::toString).collect(Collectors.toList()));

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public static JavacPluginJsr199Fields deserialize(Map<String, Object> data) {
    Boolean canReuseClasspath = (Boolean) data.get(CAN_REUSE_CLASSPATH);
    Preconditions.checkNotNull(canReuseClasspath);

    List<String> processorNames = (List<String>) data.get(PROCESSOR_NAMES);
    Preconditions.checkNotNull(processorNames);

    List<String> classpathAsStrings = (List<String>) data.get(CLASSPATH);
    Preconditions.checkNotNull(classpathAsStrings);

    List<URL> classpath =
        classpathAsStrings
            .stream()
            .map(
                s -> {
                  try {
                    return new URL(s);
                  } catch (MalformedURLException e) {
                    throw new RuntimeException(
                        String.format(
                            "Error deserializing %s: can't convert string (%s) into URL",
                            JavacPluginJsr199Fields.class.getSimpleName(), s),
                        e);
                  }
                })
            .collect(Collectors.toList());

    return JavacPluginJsr199Fields.builder()
        .setCanReuseClassLoader(canReuseClasspath)
        .setClasspath(classpath)
        .setProcessorNames(processorNames)
        .build();
  }
}

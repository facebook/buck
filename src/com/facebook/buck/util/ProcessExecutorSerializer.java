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
package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

public class ProcessExecutorSerializer {

  private static final String TYPE = "type";
  private static final String TYPE_DEFAULT = "default";
  private static final String TYPE_CONTEXTUAL = "contextual";
  private static final String CONTEXT = "context";
  private static final String DELEGATE = "delegate";

  private ProcessExecutorSerializer() {}

  public static ImmutableMap<String, Object> serialize(ProcessExecutor executor) {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    if (executor instanceof DefaultProcessExecutor) {
      builder.put(TYPE, TYPE_DEFAULT);
    } else if (executor instanceof ContextualProcessExecutor) {
      builder.put(TYPE, TYPE_CONTEXTUAL);
      ContextualProcessExecutor contextualProcessExecutor = (ContextualProcessExecutor) executor;
      builder.put(CONTEXT, contextualProcessExecutor.getContext());
      builder.put(DELEGATE, serialize(contextualProcessExecutor.getDelegate()));
    } else {
      throw new RuntimeException(
          String.format("Cannot serialize ProcessExecutor with class %s", executor.getClass()));
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  public static ProcessExecutor deserialize(Map<String, Object> data, Console console) {
    String type = (String) data.get(TYPE);
    Preconditions.checkNotNull(type, "Cannot deserialize without `%s` field", TYPE);
    if (TYPE_DEFAULT.equals(type)) {
      return new DefaultProcessExecutor(console);
    } else if (TYPE_CONTEXTUAL.equals(type)) {
      Map<String, Object> delegateData = (Map<String, Object>) data.get(DELEGATE);
      Preconditions.checkNotNull(
          delegateData,
          "Expected to find serialized data for delegate of the ContextualProcessExecutor");
      Map<String, String> context = (Map<String, String>) data.get(CONTEXT);
      Preconditions.checkNotNull(context, "Expected to find context for ContextualProcessExecutor");
      return new ContextualProcessExecutor(
          deserialize(delegateData, console), ImmutableMap.copyOf(context));
    } else {
      throw new RuntimeException(
          String.format("Cannot serialize ProcessExecutor with type %s", type));
    }
  }
}

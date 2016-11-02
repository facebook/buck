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

package com.facebook.buck.message_ipc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.Map;

public class MessageSerializer {
  private final ObjectMapper objectMapper;

  public MessageSerializer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String serializeInvocation(InvocationMessage invocation) throws JsonProcessingException {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.put("type", InvocationMessage.class.getSimpleName());
    builder.put("name", invocation.getMethodName());
    builder.put("args", invocation.getArguments());
    return objectMapper.writeValueAsString(builder.build());
  }

  ReturnResultMessage deserializeResult(String data) throws IOException {
    Map<String, Object> rep = objectMapper.readValue(
        data,
        new TypeReference<Map<String, Object>>() {});
    String type = (String) rep.get("type");
    Preconditions.checkArgument(type.equals(ReturnResultMessage.class.getSimpleName()));
    return new ReturnResultMessage(rep.get("value"));
  }
}

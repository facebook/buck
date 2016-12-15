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
import java.util.List;
import java.util.Map;

public class MessageSerializer {
  private static final String TYPE = "type";
  private static final String NAME = "name";
  private static final String ARGS = "args";
  private static final String VALUE = "value";
  private final ObjectMapper objectMapper;

  public MessageSerializer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String serializeInvocation(InvocationMessage invocation) throws JsonProcessingException {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.put(TYPE, InvocationMessage.class.getSimpleName());
    builder.put(NAME, invocation.getMethodName());
    builder.put(ARGS, invocation.getArguments());
    return objectMapper.writeValueAsString(builder.build());
  }

  @SuppressWarnings("unchecked")
  public InvocationMessage deserializeInvocation(String data) throws IOException {
    Map<String, Object> rep = objectMapper.readValue(
        data,
        new TypeReference<Map<String, Object>>() {});
    String type = (String) rep.get(TYPE);
    Preconditions.checkNotNull(
        type,
        "Unable to deserialize %s: no field %s in data (%s)",
        InvocationMessage.class.getSimpleName(), TYPE, data);
    Preconditions.checkArgument(type.equals(InvocationMessage.class.getSimpleName()));
    return new InvocationMessage(
        (String) rep.get(NAME),
        (List<Object>) rep.get(ARGS));
  }

  public String serializeResult(ReturnResultMessage message) throws JsonProcessingException {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.put(TYPE, ReturnResultMessage.class.getSimpleName());
    builder.put(VALUE, message.getValue());
    return objectMapper.writeValueAsString(builder.build());
  }

  public ReturnResultMessage deserializeResult(String data) throws IOException {
    Map<String, Object> rep = objectMapper.readValue(
        data,
        new TypeReference<Map<String, Object>>() {});
    String type = (String) rep.get(TYPE);
    Preconditions.checkNotNull(
        type,
        "Unable to deserialize %s: no field %s in data (%s)",
        ReturnResultMessage.class.getSimpleName(), TYPE, data);
    Preconditions.checkArgument(type.equals(ReturnResultMessage.class.getSimpleName()));
    return new ReturnResultMessage(rep.get(VALUE));
  }
}

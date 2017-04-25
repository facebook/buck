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

import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.hamcrest.Matchers;
import org.junit.Test;

public class MessageSerializerTest {
  @Test
  public void testSerializeInvocationMessage() throws Exception {
    InvocationMessage message = new InvocationMessage("method_name", ImmutableList.of("arg1", 42));
    MessageSerializer serializer = new MessageSerializer();
    String value = serializer.serializeInvocation(message);
    assertThat(
        value,
        Matchers.equalToObject(
            "{\"type\":\"InvocationMessage\",\"name\":\"method_name\",\"args\":[\"arg1\",42]}"));
  }

  @Test
  public void testDeserializeInvocationMessage() throws Exception {
    String value =
        "{\"type\":\"InvocationMessage\",\"name\":\"myMethod\",\"args\":[\"string\",100500]}";
    MessageSerializer serializer = new MessageSerializer();
    InvocationMessage message = serializer.deserializeInvocation(value);
    assertThat(message.getMethodName(), Matchers.equalToObject("myMethod"));
    assertThat(message.getArguments(), Matchers.equalToObject(ImmutableList.of("string", 100500)));
  }

  @Test
  public void testDeserializeInvocationMessageWithNullArgs() throws Exception {
    String value = "{\"type\":\"InvocationMessage\",\"name\":\"myMethod\",\"args\":[null,100500]}";
    MessageSerializer serializer = new MessageSerializer();
    InvocationMessage message = serializer.deserializeInvocation(value);
    assertThat(message.getMethodName(), Matchers.equalToObject("myMethod"));
    assertThat(message.getArguments().get(0), Matchers.equalTo(null));
    assertThat(message.getArguments().get(1), Matchers.equalTo(100500));
  }

  @Test
  public void testSerializeReturnResultMessage() throws Exception {
    ReturnResultMessage message = new ReturnResultMessage(ImmutableMap.of("some_value", false));
    MessageSerializer serializer = new MessageSerializer();
    String value = serializer.serializeResult(message);
    assertThat(
        value,
        Matchers.equalToObject(
            "{\"type\":\"ReturnResultMessage\",\"value\":{\"some_value\":false}}"));
  }

  @Test
  public void testDeserializeReturnResultMessage() throws Exception {
    String value = "{\"type\":\"ReturnResultMessage\",\"value\":[{\"some_value\":false}, 42]}";
    MessageSerializer serializer = new MessageSerializer();
    ReturnResultMessage message = serializer.deserializeResult(value);
    assertThat(
        message.getValue(),
        Matchers.equalToObject(ImmutableList.of(ImmutableMap.of("some_value", false), 42)));
  }
}

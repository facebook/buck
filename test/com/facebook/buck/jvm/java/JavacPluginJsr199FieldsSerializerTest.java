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

import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.net.URL;
import org.hamcrest.Matchers;
import org.junit.Test;

public class JavacPluginJsr199FieldsSerializerTest {
  @Test
  public void testSerializing() throws Exception {
    JavacPluginJsr199Fields fields =
        JavacPluginJsr199Fields.builder()
            .setCanReuseClassLoader(true)
            .setClasspath(ImmutableList.of(new URL("http://facebook.com:80/index.php")))
            .setProcessorNames(ImmutableList.of("name1", "name2"))
            .build();

    ImmutableMap<String, Object> map = JavacPluginJsr199FieldsSerializer.serialize(fields);

    JavacPluginJsr199Fields deserializedFields = JavacPluginJsr199FieldsSerializer.deserialize(map);

    assertThat(deserializedFields.getCanReuseClassLoader(), Matchers.equalTo(true));
    assertThat(
        deserializedFields.getClasspath(),
        Matchers.equalToObject(ImmutableList.of(new URL("http://facebook.com:80/index.php"))));
    assertThat(deserializedFields.getProcessorNames(), Matchers.contains("name1", "name2"));
    assertThat(deserializedFields, Matchers.equalToObject(fields));
  }
}

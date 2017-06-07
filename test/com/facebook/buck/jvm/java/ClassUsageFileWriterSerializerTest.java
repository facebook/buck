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

import static org.junit.Assert.assertThat;

import java.nio.file.Paths;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ClassUsageFileWriterSerializerTest {
  @Test
  public void testSerializingDefaultType() throws Exception {
    DefaultClassUsageFileWriter instance = new DefaultClassUsageFileWriter(Paths.get("/some/path"));
    Map<String, Object> data = ClassUsageFileWriterSerializer.serialize(instance);
    ClassUsageFileWriter outstance = ClassUsageFileWriterSerializer.deserialize(data);
    assertThat(outstance, Matchers.instanceOf(DefaultClassUsageFileWriter.class));
    DefaultClassUsageFileWriter result = (DefaultClassUsageFileWriter) outstance;
    assertThat(result.getRelativePath(), Matchers.equalToObject(instance.getRelativePath()));
  }

  @Test
  public void testSerializingNoOpType() throws Exception {
    NoOpClassUsageFileWriter instance = NoOpClassUsageFileWriter.instance();
    Map<String, Object> data = ClassUsageFileWriterSerializer.serialize(instance);
    ClassUsageFileWriter outstance = ClassUsageFileWriterSerializer.deserialize(data);
    assertThat(outstance, Matchers.instanceOf(NoOpClassUsageFileWriter.class));
  }
}

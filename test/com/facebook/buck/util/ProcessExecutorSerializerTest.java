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

import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.TestConsole;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ProcessExecutorSerializerTest {
  @Test
  public void testSerializingDefaultType() throws Exception {
    Console console = new TestConsole();
    DefaultProcessExecutor instance = new DefaultProcessExecutor(console);
    Map<String, Object> data = ProcessExecutorSerializer.serialize(instance);
    ProcessExecutor outstance = ProcessExecutorSerializer.deserialize(data, console);
    assertThat(outstance, Matchers.instanceOf(DefaultProcessExecutor.class));
  }

  @Test
  public void testSerializingContextualType() throws Exception {
    Console console = new TestConsole();
    DefaultProcessExecutor delegate = new DefaultProcessExecutor(console);
    ImmutableMap<String, String> context = ImmutableMap.of("k1", "v1", "k2", "v2");
    ContextualProcessExecutor instance = new ContextualProcessExecutor(delegate, context);
    Map<String, Object> data = ProcessExecutorSerializer.serialize(instance);
    ProcessExecutor outstance = ProcessExecutorSerializer.deserialize(data, console);
    assertThat(outstance, Matchers.instanceOf(ContextualProcessExecutor.class));
    ContextualProcessExecutor contextualProcessExecutor = (ContextualProcessExecutor) outstance;
    assertThat(contextualProcessExecutor.getContext(), Matchers.equalToObject(context));
    assertThat(contextualProcessExecutor.getDelegate(), Matchers.instanceOf(delegate.getClass()));
  }
}

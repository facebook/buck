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

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ProcessRegistryTest {

  private static final ImmutableMap<String, String> CONTEXT = ImmutableMap.of("aaa", "bbb");

  private ProcessRegistry processRegistry;

  @Before
  public void setUp() {
    processRegistry = new ProcessRegistry();
  }

  @Test
  public void testInteraction() {
    List<ProcessInfo> registeredProcesses1 = new ArrayList<>();
    List<ProcessInfo> registeredProcesses2 = new ArrayList<>();
    ProcessRegistry.ProcessRegisterCallback callback1 =
        (process, params, context) ->
            registeredProcesses1.add(new ProcessInfo(process, params, context));
    ProcessRegistry.ProcessRegisterCallback callback2 =
        (process, params, context) ->
            registeredProcesses2.add(new ProcessInfo(process, params, context));
    processRegistry.subscribe(callback1);
    processRegistry.subscribe(callback2);
    assertInfosEqual(registeredProcesses1);
    assertInfosEqual(registeredProcesses2);

    ProcessInfo info1 = new ProcessInfo(new Object(), "Proc1", CONTEXT);
    processRegistry.registerProcess(info1.process, info1.params, CONTEXT);
    assertInfosEqual(registeredProcesses1, info1);
    assertInfosEqual(registeredProcesses2, info1);

    ProcessInfo info2 = new ProcessInfo(new Object(), "Proc2", CONTEXT);
    processRegistry.registerProcess(info2.process, info2.params, CONTEXT);
    assertInfosEqual(registeredProcesses1, info1, info2);
    assertInfosEqual(registeredProcesses2, info1, info2);

    processRegistry.unsubscribe(callback1);
    assertInfosEqual(registeredProcesses1, info1, info2);
    assertInfosEqual(registeredProcesses2, info1, info2);

    ProcessInfo info3 = new ProcessInfo(new Object(), "Proc3", CONTEXT);
    processRegistry.registerProcess(info3.process, info3.params, CONTEXT);
    assertInfosEqual(registeredProcesses1, info1, info2);
    assertInfosEqual(registeredProcesses2, info1, info2, info3);

    processRegistry.unsubscribe(callback2);
    processRegistry.subscribe(callback1);

    ProcessInfo info4 = new ProcessInfo(new Object(), "Proc4", CONTEXT);
    processRegistry.registerProcess(info4.process, info4.params, CONTEXT);
    assertInfosEqual(registeredProcesses1, info1, info2, info4);
    assertInfosEqual(registeredProcesses2, info1, info2, info3);
  }

  private static void assertInfosEqual(List<ProcessInfo> list, ProcessInfo... infos) {
    assertEquals(list.size(), infos.length);
    for (int i = 0; i < infos.length; i++) {
      assertEquals(list.get(i), infos[i]);
    }
  }

  private static class ProcessInfo {
    final Object process;
    final ProcessExecutorParams params;
    final ImmutableMap<String, String> context;

    ProcessInfo(
        Object process, ProcessExecutorParams params, ImmutableMap<String, String> context) {
      this.process = process;
      this.params = params;
      this.context = context;
    }

    ProcessInfo(Object process, String executable, ImmutableMap<String, String> context) {
      this(process, ProcessExecutorParams.ofCommand(executable), context);
    }

    @Override
    public int hashCode() {
      return (process.hashCode() * 31 + params.hashCode()) * 31 + context.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ProcessInfo)) {
        return false;
      }
      ProcessInfo that = (ProcessInfo) obj;
      return process.equals(that.process)
          && params.equals(that.params)
          && context.equals(that.context);
    }
  }
}

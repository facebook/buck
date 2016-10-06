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

import com.google.common.base.Optional;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ProcessRegistryTest {

  @Test
  public void testRegisterCallback() {
    final List<ProcessInfo> registeredProcesses = new ArrayList<>();
    ProcessRegistry.ProcessRegisterCallback callback =
        new ProcessRegistry.ProcessRegisterCallback() {
          @Override
          public void call(Object process, ProcessExecutorParams params) {
            registeredProcesses.add(new ProcessInfo(process, params));
          }
        };
    ProcessRegistry.setsProcessRegisterCallback(Optional.of(callback));
    assertEquals(0, registeredProcesses.size());

    ProcessInfo info1 = new ProcessInfo(new Object(), "Proc1");
    ProcessRegistry.registerProcess(info1.process, info1.params);
    assertEquals(1, registeredProcesses.size());
    assertEquals(info1.params, registeredProcesses.get(0).params);
    assertEquals(info1.process, registeredProcesses.get(0).process);

    ProcessInfo info2 = new ProcessInfo(new Object(), "Proc2");
    ProcessRegistry.registerProcess(info2.process, info2.params);
    assertEquals(2, registeredProcesses.size());
    assertEquals(info1.params, registeredProcesses.get(0).params);
    assertEquals(info1.process, registeredProcesses.get(0).process);
    assertEquals(info2.params, registeredProcesses.get(1).params);
    assertEquals(info2.process, registeredProcesses.get(1).process);

    ProcessRegistry.setsProcessRegisterCallback(
        Optional.<ProcessRegistry.ProcessRegisterCallback>absent());
    assertEquals(2, registeredProcesses.size());

    ProcessInfo info3 = new ProcessInfo(new Object(), "Proc3");
    ProcessRegistry.registerProcess(info3.process, info3.params);
    assertEquals(2, registeredProcesses.size());
  }


  private static class ProcessInfo {
    Object process;
    ProcessExecutorParams params;

    ProcessInfo(Object process, ProcessExecutorParams params) {
      this.process = process;
      this.params = params;
    }

    ProcessInfo(Object process, String executable) {
      this(process, ProcessExecutorParams.ofCommand(executable));
    }
  }
}

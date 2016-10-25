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

package com.facebook.buck.util.perf;

import static com.facebook.buck.util.perf.ProcessTracker.ProcessResourceConsumptionEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.util.FakeInvocationInfoFactory;
import com.facebook.buck.util.FakeNuProcess;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessHelper;
import com.facebook.buck.util.FakeProcessRegistry;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessHelper;
import com.facebook.buck.util.ProcessRegistry;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.zaxxer.nuprocess.NuProcess;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ProcessTrackerTest {

  private static final ImmutableMap<String, String> CONTEXT = ImmutableMap.of("aaa", "bbb");

  private ProcessHelper processHelper;
  private ProcessRegistry processRegistry;

  @Before
  public void setUp() {
    processHelper = new FakeProcessHelper();
    processRegistry = new FakeProcessRegistry();
  }

  private static final Map<String, String> ENVIRONMENT = ImmutableMap.of("ProcessTrackerTest", "1");

  @Test
  public void testInteraction() throws Exception {
    BlockingQueue<ProcessResourceConsumptionEvent> events = new LinkedBlockingQueue<>();
    try (ProcessTrackerForTest processTracker = createProcessTracker(events)) {
      // Verify that ProcessTracker subscribes to ProcessRegistry and that calling
      // registerProcess causes ProcessTracker to start tracking the process.
      FakeNuProcess proc41 = new FakeNuProcess(41);
      processTracker.verifyNoProcessInfo(41);
      assertEquals(0, processTracker.processesInfo.size());
      processRegistry.registerProcess(proc41, createParams("proc41"), CONTEXT);
      processTracker.verifyProcessInfo(41, proc41, createParams("proc41"));
      assertEquals(1, processTracker.processesInfo.size());
      dumpEvents(events);
      assertTrue(events.isEmpty());

      // Verify that after registering a new process, both are being tracked
      FakeNuProcess proc42 = new FakeNuProcess(42);
      processRegistry.registerProcess(proc42, createParams("proc42"), CONTEXT);
      processTracker.verifyProcessInfo(41, proc41, createParams("proc41"));
      processTracker.verifyProcessInfo(42, proc42, createParams("proc42"));
      assertEquals(2, processTracker.processesInfo.size());
      dumpEvents(events);
      assertTrue(events.isEmpty());

      // Verify that after registering a process with an already tracked pid,
      // the old process info gets discarded.
      FakeNuProcess proc41b = new FakeNuProcess(41);
      processRegistry.registerProcess(proc41b, createParams("proc41b"), CONTEXT);
      processTracker.verifyProcessInfo(42, proc42, createParams("proc42"));
      processTracker.verifyProcessInfo(41, proc41b, createParams("proc41b"));
      assertEquals(2, processTracker.processesInfo.size());
      // Verify an event has been posted to the bus on remove
      dumpEvents(events);
      ProcessResourceConsumptionEvent event1 = pollEvent(events);
      assertEquals(createParams("proc41"), event1.getParams());
      dumpEvents(events);
      assertTrue(events.isEmpty());

      // Verify that processes whose pid cannot be obtained are ignored
      processRegistry.registerProcess(new FakeProcess(0), createParams("proc0"), CONTEXT);
      processTracker.verifyProcessInfo(42, proc42, createParams("proc42"));
      processTracker.verifyProcessInfo(41, proc41b, createParams("proc41b"));
      assertEquals(2, processTracker.processesInfo.size());
      dumpEvents(events);
      assertTrue(events.isEmpty());

      // Verify that ongoing processes are kept after refresh
      processTracker.runOneIteration();
      processTracker.verifyProcessInfo(42, proc42, createParams("proc42"));
      processTracker.verifyProcessInfo(41, proc41b, createParams("proc41b"));
      assertEquals(2, processTracker.processesInfo.size());
      dumpEvents(events);
      assertTrue(events.isEmpty());

      // Verify that finished processes are removed after refresh
      proc42.finish(0);
      processTracker.runOneIteration();
      processTracker.verifyProcessInfo(41, proc41b, createParams("proc41b"));
      processTracker.verifyNoProcessInfo(42);
      assertEquals(1, processTracker.processesInfo.size());
      // Verify an event has been posted to the bus on remove
      dumpEvents(events);
      ProcessResourceConsumptionEvent event2 = pollEvent(events);
      assertEquals(createParams("proc42"), event2.getParams());
      dumpEvents(events);
      assertTrue(events.isEmpty());
    }
    // verify no events are sent after closing ProcessTracker
    processRegistry.registerProcess(new FakeNuProcess(43), createParams("proc43"), CONTEXT);
    assertTrue(events.isEmpty());
  }

  private void dumpEvents(BlockingQueue<ProcessResourceConsumptionEvent> events) {
    System.err.println("Dumping events:");
    for (ProcessResourceConsumptionEvent event : events) {
      System.err.println(event.getParams());
    }
    System.err.println("");
  }

  private ProcessResourceConsumptionEvent pollEvent(
      BlockingQueue<ProcessResourceConsumptionEvent> events) throws Exception {
    return events.poll(100, TimeUnit.MILLISECONDS);
  }

  private ProcessTrackerForTest createProcessTracker(
      final BlockingQueue<ProcessResourceConsumptionEvent> events) {
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    eventBus.register(new Object() {
      @Subscribe
      public void event(ProcessResourceConsumptionEvent event) {
        if (event.getParams().getEnvironment().equals(Optional.of(ENVIRONMENT))) {
          events.add(event);
        }
      }
    });
    return new ProcessTrackerForTest(
        eventBus,
        FakeInvocationInfoFactory.create(),
        processHelper,
        processRegistry);
  }

  private static class ProcessTrackerForTest extends ProcessTracker {
    ProcessTrackerForTest(
        BuckEventBus eventBus,
        InvocationInfo invocationInfo,
        ProcessHelper processHelper,
        ProcessRegistry processRegistry) {
      super(eventBus, invocationInfo, processHelper, processRegistry);
    }

    @Override
    public void runOneIteration() throws Exception {
      super.runOneIteration();
    }

    void verifyNoProcessInfo(long pid) throws Exception {
      assertFalse(processesInfo.containsKey(pid));
    }

    void verifyProcessInfo(
        long pid,
        NuProcess process,
        ProcessExecutorParams params) throws Exception {
      ProcessInfo info = processesInfo.get(pid);
      assertNotNull(info);
      assertEquals(params.getCommand().get(0), info.params.getCommand().get(0));
      assertEquals(params, info.params);
      assertSame(process, info.process);
    }
  }

  private static ProcessExecutorParams createParams(String executable) {
    return ProcessExecutorParams.builder()
        .addCommand(executable)
        .setEnvironment(ENVIRONMENT)
        .build();
  }
}

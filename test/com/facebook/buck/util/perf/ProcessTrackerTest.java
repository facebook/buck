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
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.util.FakeInvocationInfoFactory;
import com.facebook.buck.util.FakeNuProcess;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessHelper;
import com.facebook.buck.util.FakeProcessRegistry;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessHelper;
import com.facebook.buck.util.ProcessRegistry;
import com.facebook.buck.util.ProcessResourceConsumption;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.zaxxer.nuprocess.NuProcess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Test;

public class ProcessTrackerTest {

  private static final ImmutableMap<String, String> CONTEXT = ImmutableMap.of("aaa", "bbb");

  private static final long PID = 1337;
  private static final ImmutableMap<String, String> ENVIRONMENT =
      ImmutableMap.of("ProcessTrackerTest", "1");

  private FakeProcessHelper processHelper;
  private FakeProcessRegistry processRegistry;

  @Before
  public void setUp() {
    processHelper = new FakeProcessHelper();
    processRegistry = new FakeProcessRegistry();
    processHelper.setCurrentPid(PID);
  }

  @Test
  public void testInteraction() throws Exception {
    BlockingQueue<ProcessResourceConsumptionEvent> events = new LinkedBlockingQueue<>();
    try (ProcessTrackerForTest processTracker = createProcessTracker(events)) {
      processTracker.explicitStartUp();
      processTracker.verifyThisProcessInfo(PID, "<buck-process>");
      assertEquals(1, processTracker.processesInfo.size());
      verifyEvents(ImmutableMap.of(), null, events);

      // Verify that ProcessTracker subscribes to ProcessRegistry and that calling
      // registerProcess causes ProcessTracker to start tracking the process.
      FakeNuProcess proc41 = new FakeNuProcess(41);
      processTracker.verifyNoProcessInfo(41);
      assertEquals(1, processTracker.processesInfo.size());
      processRegistry.registerProcess(proc41, createParams("proc41"), CONTEXT);
      processTracker.verifyExternalProcessInfo(41, proc41, createParams("proc41"));
      assertEquals(2, processTracker.processesInfo.size());
      verifyEvents(ImmutableMap.of(), null, events);

      // Verify that after registering a new process, both are being tracked
      FakeNuProcess proc42 = new FakeNuProcess(42);
      processRegistry.registerProcess(proc42, createParams("proc42"), CONTEXT);
      processTracker.verifyExternalProcessInfo(41, proc41, createParams("proc41"));
      processTracker.verifyExternalProcessInfo(42, proc42, createParams("proc42"));
      assertEquals(3, processTracker.processesInfo.size());
      verifyEvents(ImmutableMap.of(), null, events);

      // Verify that after registering a process with an already tracked pid,
      // the old process info gets discarded.
      FakeNuProcess proc41b = new FakeNuProcess(41);
      processRegistry.registerProcess(proc41b, createParams("proc41b"), CONTEXT);
      processTracker.verifyExternalProcessInfo(42, proc42, createParams("proc42"));
      processTracker.verifyExternalProcessInfo(41, proc41b, createParams("proc41b"));
      assertEquals(3, processTracker.processesInfo.size());
      // Verify an event has been posted to the bus on remove
      verifyEvents(ImmutableMap.of("proc41", Optional.of(createParams("proc41"))), null, events);

      // Verify that processes whose pid cannot be obtained are ignored
      processRegistry.registerProcess(new FakeProcess(0), createParams("proc0"), CONTEXT);
      processTracker.verifyExternalProcessInfo(42, proc42, createParams("proc42"));
      processTracker.verifyExternalProcessInfo(41, proc41b, createParams("proc41b"));
      assertEquals(3, processTracker.processesInfo.size());
      verifyEvents(ImmutableMap.of(), null, events);

      // Verify that ongoing processes are kept after refresh
      processTracker.explicitRunOneIteration();
      processTracker.verifyExternalProcessInfo(42, proc42, createParams("proc42"));
      processTracker.verifyExternalProcessInfo(41, proc41b, createParams("proc41b"));
      assertEquals(3, processTracker.processesInfo.size());
      verifyEvents(ImmutableMap.of(), null, events);

      // Verify that finished processes are removed after refresh
      proc42.finish(0);
      processTracker.explicitRunOneIteration();
      processTracker.verifyExternalProcessInfo(41, proc41b, createParams("proc41b"));
      processTracker.verifyNoProcessInfo(42);
      assertEquals(2, processTracker.processesInfo.size());
      // Verify an event has been posted to the bus on remove
      verifyEvents(ImmutableMap.of("proc42", Optional.of(createParams("proc42"))), null, events);

      processTracker.explicitShutDown();
      processTracker.verifyNoProcessInfo(41);
      processTracker.verifyNoProcessInfo(PID);
      assertEquals(0, processTracker.processesInfo.size());
      // Verify events for the existing processes have been posted to the bus on shut down
      verifyEvents(
          ImmutableMap.of(
              "proc41b", Optional.of(createParams("proc41b")),
              "<buck-process>", Optional.empty()),
          null,
          events);
    }
    // verify no events are sent after closing ProcessTracker
    processRegistry.registerProcess(new FakeNuProcess(43), createParams("proc43"), CONTEXT);
    verifyEvents(ImmutableMap.of(), null, events);
  }

  @Test
  public void testExternalProcessInfo() throws Exception {
    BlockingQueue<ProcessResourceConsumptionEvent> events = new LinkedBlockingQueue<>();
    try (ProcessTrackerForTest processTracker = createProcessTracker(events)) {
      FakeNuProcess proc1 = new FakeNuProcess(111);
      ProcessResourceConsumption res1 = createConsumption(42, 3, 0);
      processHelper.setProcessResourceConsumption(111, res1);

      ProcessTracker.ExternalProcessInfo info1 =
          processTracker.new ExternalProcessInfo(111, proc1, createParams("proc1"), CONTEXT);
      assertFalse(info1.hasProcessFinished());
      testProcessInfo(info1, 111, "proc1", Optional.of(createParams("proc1")), res1, events);

      FakeNuProcess proc2 = new FakeNuProcess(222);
      ProcessTracker.ExternalProcessInfo info2 =
          processTracker.new ExternalProcessInfo(222, proc2, createParams("proc2"), CONTEXT);
      assertFalse(info2.hasProcessFinished());
      testProcessInfo(info2, 222, "proc2", Optional.of(createParams("proc2")), null, events);

      proc1.finish(-3);
      assertTrue(info1.hasProcessFinished());
    }
  }

  @Test
  public void testThisProcessInfo() throws Exception {
    BlockingQueue<ProcessResourceConsumptionEvent> events = new LinkedBlockingQueue<>();
    try (ProcessTrackerForTest processTracker = createProcessTracker(events)) {
      ProcessResourceConsumption res1 = createConsumption(42, 3, 0);
      processHelper.setProcessResourceConsumption(123, res1);

      ProcessTracker.ThisProcessInfo info1 = processTracker.new ThisProcessInfo(123, "proc1");
      assertFalse(info1.hasProcessFinished());
      testProcessInfo(info1, 123, "proc1", Optional.empty(), res1, events);

      ProcessTracker.ThisProcessInfo info2 = processTracker.new ThisProcessInfo(200, "proc2");
      assertFalse(info2.hasProcessFinished());
      testProcessInfo(info2, 200, "proc2", Optional.empty(), null, events);
    }
  }

  private void testProcessInfo(
      ProcessTracker.ProcessInfo info,
      long pid,
      String name,
      Optional<ProcessExecutorParams> params,
      @Nullable ProcessResourceConsumption res1,
      BlockingQueue<ProcessResourceConsumptionEvent> events)
      throws Exception {
    info.postEvent();
    verifyEvents(
        ImmutableMap.of(name, params), ImmutableMap.of(name, Optional.ofNullable(res1)), events);

    ProcessResourceConsumption res2 = createConsumption(5, 10, 40);
    processHelper.setProcessResourceConsumption(pid, res2);
    info.updateResourceConsumption();

    // we keep track of the peak value for each metric
    ProcessResourceConsumption res3 = ProcessResourceConsumption.getPeak(res1, res2);

    info.postEvent();
    verifyEvents(ImmutableMap.of(name, params), ImmutableMap.of(name, Optional.of(res3)), events);
  }

  @Test
  public void testEventClass() {
    ProcessResourceConsumptionEvent event1 =
        new ProcessResourceConsumptionEvent(
            "name42", Optional.empty(), Optional.empty(), Optional.empty());
    assertEquals("name42", event1.getExecutableName());
    assertEquals(Optional.empty(), event1.getParams());
    assertEquals(Optional.empty(), event1.getContext());
    assertEquals(Optional.empty(), event1.getResourceConsumption());

    ProcessExecutorParams params = createParams("name100p");
    ProcessResourceConsumption res = createConsumption(123, 45, 7011);
    ProcessResourceConsumptionEvent event2 =
        new ProcessResourceConsumptionEvent(
            "name100e", Optional.of(params), Optional.of(CONTEXT), Optional.of(res));
    assertEquals("name100e", event2.getExecutableName());
    assertEquals(Optional.of(params), event2.getParams());
    assertEquals(Optional.of(CONTEXT), event2.getContext());
    assertEquals(Optional.of(res), event2.getResourceConsumption());
  }

  private static void verifyEvents(
      @Nullable Map<String, Optional<ProcessExecutorParams>> expectedParams,
      @Nullable Map<String, Optional<ProcessResourceConsumption>> expectedRes,
      BlockingQueue<ProcessResourceConsumptionEvent> events)
      throws Exception {
    dumpEvents(events);
    List<ProcessResourceConsumptionEvent> actualEvents = pollEvents(events);
    for (int i = 0; i < actualEvents.size(); i++) {
      String name = actualEvents.get(i).getExecutableName();
      if (expectedParams != null) {
        assertTrue("Unexpected event for '" + name + "'", expectedParams.containsKey(name));
        assertEquals(expectedParams.get(name), actualEvents.get(i).getParams());
      }
      if (expectedRes != null) {
        assertTrue("Unexpected event for '" + name + "'", expectedRes.containsKey(name));
        assertEquals(expectedRes.get(name), actualEvents.get(i).getResourceConsumption());
      }
    }
    if (expectedParams != null) {
      assertEquals(expectedParams.size(), actualEvents.size());
    }
    if (expectedRes != null) {
      assertEquals(expectedRes.size(), actualEvents.size());
    }
  }

  private static List<ProcessResourceConsumptionEvent> pollEvents(
      BlockingQueue<ProcessResourceConsumptionEvent> events) throws Exception {
    List<ProcessResourceConsumptionEvent> res = new ArrayList<>();
    while (!events.isEmpty()) {
      ProcessResourceConsumptionEvent event = events.poll(0, TimeUnit.MILLISECONDS);
      res.add(event);
    }
    return res;
  }

  private static void dumpEvents(BlockingQueue<ProcessResourceConsumptionEvent> events) {
    System.err.println("Dumping events: " + events.size());
    for (ProcessResourceConsumptionEvent event : events) {
      System.err.println("{'" + event.getExecutableName() + "', " + event.getParams() + "}");
    }
    System.err.println();
  }

  private ProcessTrackerForTest createProcessTracker(
      BlockingQueue<ProcessResourceConsumptionEvent> events) {
    BuckEventBus eventBus = BuckEventBusForTests.newInstance();
    eventBus.register(
        new Object() {
          @Subscribe
          public void event(ProcessResourceConsumptionEvent event) {
            events.add(event);
          }
        });
    return new ProcessTrackerForTest(
        eventBus, FakeInvocationInfoFactory.create(), processHelper, processRegistry);
  }

  private static class ProcessTrackerForTest extends ProcessTracker {
    ProcessTrackerForTest(
        BuckEventBus eventBus,
        InvocationInfo invocationInfo,
        ProcessHelper processHelper,
        ProcessRegistry processRegistry) {
      super(eventBus, invocationInfo, processHelper, processRegistry, /* isDaemon */ false, false);
    }

    void explicitStartUp() throws Exception {
      super.startUp();
    }

    void explicitRunOneIteration() throws Exception {
      super.runOneIteration();
    }

    void explicitShutDown() throws Exception {
      super.shutDown();
    }

    @Override
    protected void startUp() {}

    @Override
    public void runOneIteration() {}

    @Override
    public void shutDown() {}

    void verifyNoProcessInfo(long pid) {
      assertFalse(processesInfo.containsKey(pid));
    }

    void verifyThisProcessInfo(long pid, String name) {
      ProcessInfo info = processesInfo.get(pid);
      assertThat(info, CoreMatchers.instanceOf(ThisProcessInfo.class));
      assertEquals(pid, ((ThisProcessInfo) info).pid);
      assertSame(name, ((ThisProcessInfo) info).name);
    }

    void verifyExternalProcessInfo(long pid, NuProcess process, ProcessExecutorParams params) {
      ProcessInfo info = processesInfo.get(pid);
      assertThat(info, CoreMatchers.instanceOf(ExternalProcessInfo.class));
      assertEquals(pid, ((ExternalProcessInfo) info).pid);
      assertSame(process, ((ExternalProcessInfo) info).process);
      assertEquals(params, ((ExternalProcessInfo) info).params);
    }
  }

  private static ProcessResourceConsumption createConsumption(long cpu, long mem, long io) {
    return ProcessResourceConsumption.builder()
        .setMemResident(0)
        .setMemSize(mem)
        .setCpuReal(cpu)
        .setCpuUser(1)
        .setCpuSys(2)
        .setCpuTotal(3)
        .setIoBytesRead(io)
        .setIoBytesWritten(0)
        .setIoTotal(io)
        .build();
  }

  private static ProcessExecutorParams createParams(String executable) {
    return ProcessExecutorParams.builder()
        .addCommand(executable)
        .setEnvironment(ENVIRONMENT)
        .build();
  }
}

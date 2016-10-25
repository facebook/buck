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

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.ProcessHelper;
import com.facebook.buck.util.ProcessRegistry;
import com.facebook.buck.util.ProcessResourceConsumption;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.ServiceManager;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tracker that periodically probes for external processes resource consumption.
 *
 * Resource consumption has to be gathered periodically because it can only be retrieved while
 * the process is still alive and we have no way of knowing or even controlling when the process
 * is going to finish (assuming it finishes execution on its own). Furthermore, for some metrics
 * (such as memory usage) only the current values get reported and we need to keep track of peak
 * usage manually. Gathering only the current values just before the process finishes (assuming
 * this was possible) would likely be highly inaccurate anyways as the process probably released
 * most of its resources by that time.
 */
public class ProcessTracker extends AbstractScheduledService implements AutoCloseable {

  private static final Logger LOG = Logger.get(ProcessTracker.class);

  private final BuckEventBus eventBus;
  private final InvocationInfo invocationInfo;
  private final ServiceManager serviceManager;
  private final ProcessHelper processHelper;
  private final ProcessRegistry processRegistry;

  private final ProcessRegistry.ProcessRegisterCallback processRegisterCallback =
      this::registerProcess;

  // Map pid -> info
  @VisibleForTesting final Map<Long, ProcessInfo> processesInfo = new ConcurrentHashMap<>();

  public ProcessTracker(BuckEventBus buckEventBus, InvocationInfo invocationInfo) {
    this(buckEventBus, invocationInfo, ProcessHelper.getInstance(), ProcessRegistry.getInstance());
  }

  @VisibleForTesting
  ProcessTracker(
      BuckEventBus buckEventBus,
      InvocationInfo invocationInfo,
      ProcessHelper processHelper,
      ProcessRegistry processRegistry) {
    this.eventBus = buckEventBus;
    this.invocationInfo = invocationInfo;
    this.serviceManager = new ServiceManager(ImmutableList.of(this));
    this.processHelper = processHelper;
    this.processRegistry = processRegistry;
    serviceManager.startAsync();
    this.processRegistry.subscribe(processRegisterCallback);
  }

  private void registerProcess(
      Object process,
      ProcessExecutorParams params,
      ImmutableMap<String, String> context) {
    Long pid = processHelper.getPid(process);
    LOG.verbose("registerProcess: pid: %s, cmd: %s", pid, params.getCommand());
    if (pid == null) {
      return;
    }
    ProcessResourceConsumption res = processHelper.getProcessResourceConsumption(pid);
    ProcessInfo old = processesInfo.put(pid, new ProcessInfo(process, params, context, res));
    if (old != null) {
      old.close();
    }
  }

  private void refreshProcessesInfo() {
    LOG.verbose("refreshProcessesInfo: processes before: %d", processesInfo.size());
    Iterator<Map.Entry<Long, ProcessInfo>> it;
    for (it = processesInfo.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<Long, ProcessInfo> entry = it.next();
      Long pid = entry.getKey();
      ProcessInfo info = entry.getValue();
      ProcessResourceConsumption res = processHelper.getProcessResourceConsumption(pid);
      if (res != null) {
        info.update(res);
      }
      if (processHelper.hasProcessFinished(info.process)) {
        info.close();
        it.remove();
      }
    }
    LOG.verbose("refreshProcessesInfo: processes after: %d", processesInfo.size());
  }

  @Override
  protected void runOneIteration() throws Exception {
    try {
      GlobalStateManager.singleton().getThreadToCommandRegister().register(
          Thread.currentThread().getId(),
          invocationInfo.getCommandId());
      refreshProcessesInfo();
    } catch (Exception e) {
      LOG.warn(e, "Failed to refresh process info.");
      throw e;
    }
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(0L, 1000L, TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    processRegistry.unsubscribe(processRegisterCallback);
    serviceManager.stopAsync();
  }

  @VisibleForTesting
  class ProcessInfo {
    final AtomicBoolean isClosed = new AtomicBoolean(false);
    final Object process;
    final ProcessExecutorParams params;
    final ImmutableMap<String, String> context;
    ProcessResourceConsumption resourceConsumption;

    ProcessInfo(
        Object process,
        ProcessExecutorParams params,
        ImmutableMap<String, String> context,
        ProcessResourceConsumption res) {
      this.process = process;
      this.params = params;
      this.context = context;
      this.resourceConsumption = res;
    }

    void update(ProcessResourceConsumption res) {
      resourceConsumption = ProcessResourceConsumption.getPeak(resourceConsumption, res);
      // update stats, but don't send events as that could be costly
    }

    void close() {
      if (isClosed.getAndSet(true)) {
        return;
      }
      LOG.verbose("Process resource consumption: %s\n%s", params.getCommand(), resourceConsumption);
      eventBus.post(new ProcessResourceConsumptionEvent(params, context, resourceConsumption));
    }
  }

  public static class ProcessResourceConsumptionEvent extends AbstractBuckEvent {
    private final ProcessExecutorParams params;
    private final ImmutableMap<String, String> context;
    private final ProcessResourceConsumption resourceConsumption;

    public ProcessResourceConsumptionEvent(
        ProcessExecutorParams params,
        ImmutableMap<String, String> context,
        ProcessResourceConsumption res) {
      super(EventKey.unique());
      this.params = params;
      this.context = context;
      this.resourceConsumption = res;
    }

    public ProcessExecutorParams getParams() {
      return params;
    }

    public ImmutableMap<String, String> getContext() {
      return context;
    }

    public ProcessResourceConsumption getResourceConsumption() {
      return resourceConsumption;
    }

    @Override
    protected String getValueString() {
      return "";
    }

    @Override
    public String getEventName() {
      return "";
    }
  }
}

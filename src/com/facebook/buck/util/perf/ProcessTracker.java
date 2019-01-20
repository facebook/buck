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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A tracker that periodically probes for external processes resource consumption.
 *
 * <p>Resource consumption has to be gathered periodically because it can only be retrieved while
 * the process is still alive and we have no way of knowing or even controlling when the process is
 * going to finish (assuming it finishes execution on its own). Furthermore, for some metrics (such
 * as memory usage) only the current values get reported and we need to keep track of peak usage
 * manually. Gathering only the current values just before the process finishes (assuming this was
 * possible) would likely be highly inaccurate anyways as the process probably released most of its
 * resources by that time.
 */
public class ProcessTracker extends AbstractScheduledService implements AutoCloseable {

  private static final Logger LOG = Logger.get(ProcessTracker.class);

  private final BuckEventBus eventBus;
  private final InvocationInfo invocationInfo;
  private final ServiceManager serviceManager;
  private final ProcessHelper processHelper;
  private final ProcessRegistry processRegistry;
  private final boolean isDaemon;
  private final boolean deepEnabled;

  private final ProcessRegistry.ProcessRegisterCallback processRegisterCallback =
      this::registerProcess;

  // Map pid -> info
  @VisibleForTesting final Map<Long, ProcessInfo> processesInfo = new ConcurrentHashMap<>();

  public ProcessTracker(
      BuckEventBus buckEventBus,
      InvocationInfo invocationInfo,
      boolean isDaemon,
      boolean deepEnabled) {
    this(
        buckEventBus,
        invocationInfo,
        ProcessHelper.getInstance(),
        ProcessRegistry.getInstance(),
        isDaemon,
        deepEnabled);
  }

  @VisibleForTesting
  ProcessTracker(
      BuckEventBus buckEventBus,
      InvocationInfo invocationInfo,
      ProcessHelper processHelper,
      ProcessRegistry processRegistry,
      boolean isDaemon,
      boolean deepEnabled) {
    this.eventBus = buckEventBus;
    this.invocationInfo = invocationInfo;
    this.serviceManager = new ServiceManager(ImmutableList.of(this));
    this.processHelper = processHelper;
    this.processRegistry = processRegistry;
    this.isDaemon = isDaemon;
    this.deepEnabled = deepEnabled;
    serviceManager.startAsync();
    this.processRegistry.subscribe(processRegisterCallback);
  }

  private void registerThisProcess() {
    Long pid = processHelper.getPid();
    LOG.verbose("registerThisProcess: pid: %s, isDaemon: %s", pid, isDaemon);
    if (pid == null) {
      return;
    }
    String name = isDaemon ? "<buck-daemon-process>" : "<buck-process>";
    processesInfo.put(pid, new ThisProcessInfo(pid, name));
  }

  private void registerProcess(
      Object process, ProcessExecutorParams params, ImmutableMap<String, String> context) {
    Long pid = processHelper.getPid(process);
    LOG.verbose("registerProcess: pid: %s, cmd: %s", pid, params.getCommand());
    if (pid == null) {
      return;
    }
    ProcessInfo info = new ExternalProcessInfo(pid, process, params, context);
    ProcessInfo old = processesInfo.put(pid, info);
    if (old != null) {
      old.postEvent();
    }
  }

  private void refreshProcessesInfo(boolean isTrackerShuttingDown) {
    LOG.verbose("refreshProcessesInfo: processes before: %d", processesInfo.size());
    Iterator<Map.Entry<Long, ProcessInfo>> it;
    for (it = processesInfo.entrySet().iterator(); it.hasNext(); ) {
      ProcessInfo info = it.next().getValue();
      info.updateResourceConsumption();
      if (isTrackerShuttingDown || info.hasProcessFinished()) {
        info.postEvent();
        it.remove();
      }
    }
    LOG.verbose("refreshProcessesInfo: processes after: %d", processesInfo.size());
  }

  @Override
  protected void startUp() {
    LOG.debug("startUp");
    registerThisProcess();
  }

  @Override
  protected void runOneIteration() {
    GlobalStateManager.singleton()
        .getThreadToCommandRegister()
        .register(Thread.currentThread().getId(), invocationInfo.getCommandId());
    refreshProcessesInfo(/* isShuttingDown */ false);
  }

  @Override
  protected void shutDown() {
    LOG.debug("shutDown");
    refreshProcessesInfo(/* isShuttingDown */ true);
  }

  @Override
  protected AbstractScheduledService.Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(0L, 1000L, TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    processRegistry.unsubscribe(processRegisterCallback);
    serviceManager.stopAsync();
  }

  @VisibleForTesting
  interface ProcessInfo {
    boolean hasProcessFinished();

    void updateResourceConsumption();

    void postEvent();
  }

  @VisibleForTesting
  class ExternalProcessInfo implements ProcessInfo {
    final long pid;
    final Object process;
    final ProcessExecutorParams params;
    final ImmutableMap<String, String> context;
    @Nullable ProcessResourceConsumption resourceConsumption;

    ExternalProcessInfo(
        long pid,
        Object process,
        ProcessExecutorParams params,
        ImmutableMap<String, String> context) {
      this.pid = pid;
      this.process = Objects.requireNonNull(process);
      this.params = Objects.requireNonNull(params);
      this.context = Objects.requireNonNull(context);
      updateResourceConsumption();
    }

    @Override
    public boolean hasProcessFinished() {
      // It would be perhaps nicer to do something like {@code !processHelper.isProcessRunning(pid)}
      // because we wouldn't need the {@link Process} instance here. However, going through
      // {@link Process} is more reliable.
      return processHelper.hasProcessFinished(process);
    }

    @Override
    public void updateResourceConsumption() {
      ProcessResourceConsumption res =
          deepEnabled
              ? processHelper.getTotalResourceConsumption(pid)
              : processHelper.getProcessResourceConsumption(pid);
      resourceConsumption = ProcessResourceConsumption.getPeak(resourceConsumption, res);
    }

    @Override
    public void postEvent() {
      LOG.verbose("Process resource consumption: %s\n%s", params, resourceConsumption);
      eventBus.post(
          new ProcessResourceConsumptionEvent(
              params.getCommand().get(0),
              Optional.of(params),
              Optional.of(context),
              Optional.ofNullable(resourceConsumption)));
    }
  }

  @VisibleForTesting
  class ThisProcessInfo implements ProcessInfo {
    final long pid;
    final String name;
    @Nullable ProcessResourceConsumption resourceConsumption;

    ThisProcessInfo(long pid, String name) {
      this.pid = pid;
      this.name = Objects.requireNonNull(name);
      updateResourceConsumption();
    }

    @Override
    public boolean hasProcessFinished() {
      // We wouldn't be here if this process has finished :)
      return false;
    }

    @Override
    public void updateResourceConsumption() {
      ProcessResourceConsumption res =
          deepEnabled
              ? processHelper.getTotalResourceConsumption(pid)
              : processHelper.getProcessResourceConsumption(pid);
      resourceConsumption = ProcessResourceConsumption.getPeak(resourceConsumption, res);
    }

    @Override
    public void postEvent() {
      LOG.verbose("Process resource consumption: %s\n%s", name, resourceConsumption);
      eventBus.post(
          new ProcessResourceConsumptionEvent(
              name, Optional.empty(), Optional.empty(), Optional.ofNullable(resourceConsumption)));
    }
  }

  public static class ProcessResourceConsumptionEvent extends AbstractBuckEvent {
    private final String executableName;
    private final Optional<ProcessExecutorParams> params;
    private final Optional<ImmutableMap<String, String>> context;
    private final Optional<ProcessResourceConsumption> resourceConsumption;

    public ProcessResourceConsumptionEvent(
        String executableName,
        Optional<ProcessExecutorParams> params,
        Optional<ImmutableMap<String, String>> context,
        Optional<ProcessResourceConsumption> resourceConsumption) {
      super(EventKey.unique());
      this.executableName = Objects.requireNonNull(executableName);
      this.params = params;
      this.context = context;
      this.resourceConsumption = resourceConsumption;
    }

    public String getExecutableName() {
      return executableName;
    }

    public Optional<ProcessExecutorParams> getParams() {
      return params;
    }

    public Optional<ImmutableMap<String, String>> getContext() {
      return context;
    }

    public Optional<ProcessResourceConsumption> getResourceConsumption() {
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

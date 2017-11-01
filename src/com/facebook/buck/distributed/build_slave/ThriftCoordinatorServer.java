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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.thrift.CoordinatorService;
import com.facebook.buck.distributed.thrift.GetWorkRequest;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftException;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.apache.thrift.TException;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TTransportException;

public class ThriftCoordinatorServer implements Closeable {

  /** Listen to ThriftCoordinatorServer events. */
  public interface EventListener {

    void onThriftServerStarted(String address, int port) throws IOException;

    void onThriftServerClosing(int buildExitCode) throws IOException;
  }

  public static final int UNEXPECTED_STOP_EXIT_CODE = 42;
  public static final int GET_WORK_FAILED_EXIT_CODE = 43;
  public static final int TEXCEPTION_EXIT_CODE = 44;

  private static final Logger LOG = Logger.get(ThriftCoordinatorServer.class);

  private static final long MAX_TEAR_DOWN_MILLIS = TimeUnit.SECONDS.toMillis(2);
  private static final long MAX_DIST_BUILD_DURATION_MILLIS = TimeUnit.HOURS.toMillis(2);

  private final DistBuildTraceTracker chromeTraceTracker;
  private final CoordinatorService.Processor<CoordinatorService.Iface> processor;
  private final Object lock;
  private final CompletableFuture<Integer> exitCodeFuture;
  private final StampedeId stampedeId;
  private final ThriftCoordinatorServer.EventListener eventListener;

  private volatile OptionalInt port;

  private volatile CoordinatorService.Iface handler;

  @Nullable private volatile TThreadedSelectorServer server;
  @Nullable private Thread serverThread;

  public ThriftCoordinatorServer(
      OptionalInt port,
      ListenableFuture<BuildTargetsQueue> queue,
      StampedeId stampedeId,
      EventListener eventListener) {
    this.eventListener = eventListener;
    this.stampedeId = stampedeId;
    this.lock = new Object();
    this.exitCodeFuture = new CompletableFuture<>();
    this.chromeTraceTracker = new DistBuildTraceTracker(stampedeId);
    this.port = port;
    this.handler = new IdleCoordinatorService();
    CoordinatorServiceHandler handlerWrapper = new CoordinatorServiceHandler();
    this.processor = new CoordinatorService.Processor<>(handlerWrapper);
    queue.addListener(() -> switchToActiveMode(queue), MoreExecutors.directExecutor());
  }

  public ThriftCoordinatorServer start() throws IOException {
    synchronized (lock) {
      TNonblockingServerSocket transport;
      try {
        transport = new TNonblockingServerSocket(this.port.orElse(0));
        // If we initially specified port zero, we would now have the correct value.
        this.port = OptionalInt.of(transport.getPort());
      } catch (TTransportException e) {
        throw new ThriftException(e);
      }

      TThreadedSelectorServer.Args serverArgs = new TThreadedSelectorServer.Args(transport);
      serverArgs.processor(processor);
      server = new TThreadedSelectorServer(serverArgs);
      serverThread = new Thread(() -> Preconditions.checkNotNull(server).serve());
      serverThread.start();
    }

    eventListener.onThriftServerStarted(InetAddress.getLocalHost().getHostName(), port.getAsInt());
    return this;
  }

  private ThriftCoordinatorServer stop() throws IOException {
    eventListener.onThriftServerClosing(exitCodeFuture.getNow(UNEXPECTED_STOP_EXIT_CODE));
    synchronized (lock) {
      Preconditions.checkNotNull(server, "Server has already been stopped.").stop();
      server = null;
      try {
        Preconditions.checkNotNull(serverThread).join(MAX_TEAR_DOWN_MILLIS);
      } catch (InterruptedException e) {
        throw new IOException("Coordinator thrift server took too long to tear down.", e);
      } finally {
        serverThread = null;
      }
    }

    return this;
  }

  public int getPort() {
    if (port.isPresent()) {
      return port.getAsInt();
    }
    throw new RuntimeException("Port is unknown since the coordinator server is not up yet.");
  }

  @Override
  public void close() throws IOException {
    if (server != null) {
      stop();
    }
  }

  /** Create a snapshot of dist build trace. */
  public DistBuildTrace traceSnapshot() {
    synchronized (lock) {
      return chromeTraceTracker.snapshot();
    }
  }

  public Future<Integer> getExitCode() {
    return exitCodeFuture;
  }

  public int waitUntilBuildCompletesAndReturnExitCode() {
    try {
      LOG.verbose("Coordinator going into blocking wait mode...");
      return getExitCode().get(MAX_DIST_BUILD_DURATION_MILLIS, TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException | InterruptedException e) {
      LOG.error(e);
      throw new RuntimeException("The distributed build Coordinator was interrupted.", e);
    }
  }

  private void switchToActiveMode(Future<BuildTargetsQueue> queue) {
    Preconditions.checkState(queue.isDone());
    try {
      MinionWorkloadAllocator allocator = new MinionWorkloadAllocator(queue.get());
      this.handler = new ActiveCoordinatorService(allocator, exitCodeFuture, chromeTraceTracker);
    } catch (InterruptedException | ExecutionException e) {
      String msg = "Failed to create the BuildTargetsQueue.";
      LOG.error(msg);
      throw new RuntimeException(msg, e);
    }
  }

  private class CoordinatorServiceHandler implements CoordinatorService.Iface {

    @Override
    public GetWorkResponse getWork(GetWorkRequest request) {
      try {
        return getWorkUnsafe(request);
      } catch (Throwable e) {
        LOG.error(
            "getWork failed, but it shouldn't;"
                + " internal state may be corrupted, so exiting coordinator",
            e);
        exitCodeFuture.complete(GET_WORK_FAILED_EXIT_CODE);
        throw e;
      }
    }

    private GetWorkResponse getWorkUnsafe(GetWorkRequest request) {
      LOG.info(
          String.format(
              "Got GetWorkRequest from minion [%s]. [%s] targets finished. [%s] units requested",
              request.getMinionId(),
              request.getFinishedTargets().size(),
              request.getMaxWorkUnitsToFetch()));

      checkBuildId(request.getStampedeId());
      Preconditions.checkArgument(request.isSetMinionId());
      Preconditions.checkArgument(request.isSetLastExitCode());

      synchronized (lock) {
        try {
          return handler.getWork(request);
        } catch (TException e) {
          exitCodeFuture.complete(TEXCEPTION_EXIT_CODE);
          throw new RuntimeException(e);
        }
      }
    }

    private void checkBuildId(StampedeId buildId) {
      Preconditions.checkArgument(
          stampedeId.equals(buildId),
          "Request stampede build id [%s] does not match the current build id [%s].",
          buildId.getId(),
          stampedeId.getId());
    }
  }
}

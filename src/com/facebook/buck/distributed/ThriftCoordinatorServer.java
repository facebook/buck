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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.CoordinatorService;
import com.facebook.buck.distributed.thrift.GetWorkRequest;
import com.facebook.buck.distributed.thrift.GetWorkResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.distributed.thrift.WorkUnit;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftException;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
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

  private static final Logger LOG = Logger.get(ThriftCoordinatorServer.class);

  private static final long MAX_TEAR_DOWN_MILLIS = TimeUnit.SECONDS.toMillis(2);
  private static final long MAX_DIST_BUILD_DURATION_MILLIS = TimeUnit.HOURS.toMillis(2);

  private final MinionWorkloadAllocator allocator;
  private final int port;
  private final CoordinatorServiceHandler handler;
  private final CoordinatorService.Processor<CoordinatorService.Iface> processor;
  private final Object lock;
  private final CompletableFuture<Integer> exitCodeFuture;
  private final StampedeId stampedeId;
  private final ThriftCoordinatorServer.EventListener eventListener;

  @Nullable private TNonblockingServerSocket transport;
  @Nullable private TThreadedSelectorServer server;
  @Nullable private Thread serverThread;

  public ThriftCoordinatorServer(
      int port,
      BuildTargetsQueue queue,
      StampedeId stampedeId,
      ThriftCoordinatorServer.EventListener eventListener) {
    this.eventListener = eventListener;
    this.stampedeId = stampedeId;
    this.lock = new Object();
    this.exitCodeFuture = new CompletableFuture<>();
    this.allocator = new MinionWorkloadAllocator(queue);
    this.port = port;
    this.handler = new CoordinatorServiceHandler();
    this.processor = new CoordinatorService.Processor<>(handler);
  }

  public ThriftCoordinatorServer start() throws IOException {
    synchronized (lock) {
      try {
        transport = new TNonblockingServerSocket(this.port);
      } catch (TTransportException e) {
        throw new ThriftException(e);
      }

      TThreadedSelectorServer.Args serverArgs = new TThreadedSelectorServer.Args(transport);
      serverArgs.processor(processor);
      server = new TThreadedSelectorServer(serverArgs);
      serverThread = new Thread(() -> Preconditions.checkNotNull(server).serve());
      serverThread.start();
    }

    eventListener.onThriftServerStarted(InetAddress.getLocalHost().getHostName(), port);
    return this;
  }

  public ThriftCoordinatorServer stop() throws IOException {
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
    return port;
  }

  @Override
  public void close() throws IOException {
    if (server != null) {
      stop();
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

  private class CoordinatorServiceHandler implements CoordinatorService.Iface {
    @Override
    public GetWorkResponse getWork(GetWorkRequest request) {
      LOG.info(
          String.format(
              "Got GetWorkRequest from minion [%s]. [%s] targets finished. [%s] units requested",
              request.getMinionId(),
              request.getFinishedTargets().size(),
              request.getMaxWorkUnitsToFetch()));

      checkBuildId(request.getStampedeId());
      Preconditions.checkArgument(request.isSetMinionId());
      Preconditions.checkArgument(request.isSetLastExitCode());

      // Create the response with some defaults
      GetWorkResponse response = new GetWorkResponse();
      response.setContinueBuilding(true);
      response.setWorkUnits(new ArrayList<>());

      synchronized (lock) {
        // There should be no more requests after the coordinator has started shutting down.
        Preconditions.checkArgument(!exitCodeFuture.isDone());

        // If the minion died, then kill the whole build.
        if (request.getLastExitCode() != 0) {
          LOG.error(
              String.format(
                  "Got non zero exit code in GetWorkRequest from minion [%s]. Exit code [%s]",
                  request.getMinionId(), request.getLastExitCode()));
          exitCodeFuture.complete(request.getLastExitCode());
          response.setContinueBuilding(false);
          return response;
        }

        List<WorkUnit> newWorkUnitsForMinion =
            allocator.dequeueZeroDependencyNodes(
                request.getMinionId(),
                request.getFinishedTargets(),
                request.getMaxWorkUnitsToFetch());

        // If the build is already finished (or just finished with this update, then signal this to
        // the minion.
        if (allocator.isBuildFinished()) {
          exitCodeFuture.complete(0);
          LOG.info(
              String.format(
                  "Minion [%s] is being told to exit because the build has finished.",
                  request.minionId));
          response.setContinueBuilding(false);
        } else {
          response.setWorkUnits(newWorkUnitsForMinion);
        }

        return response;
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

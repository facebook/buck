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
import com.facebook.buck.distributed.thrift.FinishedBuildingRequest;
import com.facebook.buck.distributed.thrift.FinishedBuildingResponse;
import com.facebook.buck.distributed.thrift.GetTargetsToBuildAction;
import com.facebook.buck.distributed.thrift.GetTargetsToBuildRequest;
import com.facebook.buck.distributed.thrift.GetTargetsToBuildResponse;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ThriftException;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.Closeable;
import java.io.IOException;
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

  private static final Logger LOG = Logger.get(ThriftCoordinatorServer.class);

  private static final long MAX_TEAR_DOWN_MILLIS = TimeUnit.SECONDS.toMillis(2);
  private static final long MAX_DIST_BUILD_DURATION_MILLIS = TimeUnit.HOURS.toMillis(2);

  // TODO(ruibm): Find some heuristic to compute this.
  private static final int MAX_TARGETS_ALLOCATED_PER_MINION = 2;

  private final MinionWorkloadAllocator allocator;
  private final int port;
  private final CoordinatorServiceHandler handler;
  private final CoordinatorService.Processor<CoordinatorService.Iface> processor;
  private final Object lock;
  private final CompletableFuture<Integer> exitCodeFuture;
  private final StampedeId stampedeId;

  @Nullable private TNonblockingServerSocket transport;
  @Nullable private TThreadedSelectorServer server;
  @Nullable private Thread serverThread;

  public ThriftCoordinatorServer(int port, BuildTargetsQueue queue, StampedeId stampedeId) {
    this.stampedeId = stampedeId;
    this.lock = new Object();
    this.exitCodeFuture = new CompletableFuture<>();
    this.allocator = new MinionWorkloadAllocator(queue, MAX_TARGETS_ALLOCATED_PER_MINION);
    this.port = port;
    this.handler = new CoordinatorServiceHandler();
    this.processor = new CoordinatorService.Processor<CoordinatorService.Iface>(handler);
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

    return this;
  }

  public ThriftCoordinatorServer stop() throws IOException {
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

  private void setBuildExitCode(int exitCode) {
    exitCodeFuture.complete(exitCode);
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
    public GetTargetsToBuildResponse getTargetsToBuild(GetTargetsToBuildRequest request)
        throws TException {
      LOG.debug(
          String.format("Minion [%s] is requesting for new targets to build.", request.minionId));
      checkBuildId(request.getStampedeId());
      synchronized (lock) {
        Preconditions.checkArgument(request.isSetMinionId());

        GetTargetsToBuildResponse response = new GetTargetsToBuildResponse();
        if (allocator.isBuildFinished()) {
          LOG.debug(
              String.format(
                  "Minion [%s] is being told to exit because the build has finished.",
                  request.minionId));
          return response.setAction(GetTargetsToBuildAction.CLOSE_CLIENT);
        }

        ImmutableList<String> targets = allocator.getTargetsToBuild(request.getMinionId());
        if (targets.isEmpty()) {
          LOG.debug(
              String.format(
                  "Minion [%s] is being told to retry getting more workload later.",
                  request.minionId));
          return response.setAction(GetTargetsToBuildAction.RETRY_LATER);
        } else {
          LOG.debug(
              String.format(
                  "Minion [%s] is being handed [%d] BuildTargets to build: [%s]",
                  request.minionId, targets.size(), Joiner.on(", ").join(targets)));
          return response.setAction(GetTargetsToBuildAction.BUILD_TARGETS).setBuildTargets(targets);
        }
      }
    }

    @Override
    public FinishedBuildingResponse finishedBuilding(FinishedBuildingRequest request)
        throws TException {
      LOG.info(String.format("Minion [%s] has finished building.", request.getMinionId()));
      checkBuildId(request.getStampedeId());
      synchronized (lock) {
        Preconditions.checkArgument(request.isSetMinionId());
        Preconditions.checkArgument(request.isSetBuildExitCode());
        FinishedBuildingResponse response = new FinishedBuildingResponse();
        if (request.getBuildExitCode() != 0) {
          setBuildExitCode(request.getBuildExitCode());
          response.setContinueBuilding(false);
        } else {
          allocator.finishedBuildingTargets(request.getMinionId());
          if (getExitCode().isDone()) {
            response.setContinueBuilding(false);
          } else {
            if (allocator.isBuildFinished()) {
              // Build has finished in all Minions successfully!!
              setBuildExitCode(0);
              response.setContinueBuilding(false);
            } else {
              response.setContinueBuilding(true);
            }
          }
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

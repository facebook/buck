/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.rules.modern.builders;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import com.facebook.buck.core.build.engine.BuildStrategyContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy.StrategyBuildResult;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionHandle;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient.ExecutionResult;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionStrategyConfig;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.impl.NoOpModernBuildRule;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RemoteExecutionStrategyTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException expectedException = ExpectedException.none();
  private final GrpcProtocol protocol = new GrpcProtocol();

  private final byte[] missingData = "data".getBytes(Charsets.UTF_8);
  private final Digest missingDigest = protocol.computeDigest(missingData);

  private RemoteExecutionClients clients;
  private ListeningExecutorService service;
  private RemoteExecutionStrategy strategy;

  @After
  public void tearDown() throws Exception {
    strategy.close();
    clients.close();
    service.shutdownNow();
  }

  public StrategyBuildResult beginBuild() {
    this.service = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    RemoteExecutionStrategyConfig strategyConfig = new TestRemoteExecutionConfig();

    RemoteExecutionActionInfo actionInfo =
        RemoteExecutionActionInfo.of(
            protocol.computeDigest(new byte[] {1}),
            ImmutableList.of(
                UploadDataSupplier.of(missingDigest, () -> new ByteArrayInputStream(missingData))),
            missingData.length,
            ImmutableList.of());

    Path cellPathPrefix = tmp.getRoot();

    RemoteExecutionHelper mbrHelper =
        new RemoteExecutionHelper() {
          @Override
          public boolean supportsRemoteExecution(ModernBuildRule<?> instance) {
            return true;
          }

          @Override
          public RemoteExecutionActionInfo prepareRemoteExecution(
              ModernBuildRule<?> rule1,
              Predicate<Digest> requiredDataPredicate,
              WorkerRequirements workerRequirements) {
            return actionInfo;
          }

          @Override
          public Path getCellPathPrefix() {
            return cellPathPrefix;
          }
        };

    this.strategy =
        new RemoteExecutionStrategy(
            BuckEventBusForTests.newInstance(),
            strategyConfig,
            clients,
            MetadataProviderFactory.emptyMetadataProvider(),
            mbrHelper,
            service);

    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance(filesystem, "//some:target");
    ModernBuildRule rule = new NoOpModernBuildRule(target, filesystem, ruleFinder);

    BuildStrategyContext strategyContext = new SimpleBuildStrategyContext(rule, service);
    return strategy.build(rule, strategyContext);
  }

  @Test
  public void testCancellationDuringUpload() throws Exception {
    SettableFuture<Runnable> completer = SettableFuture.create();

    clients =
        new SimpleRemoteExecutionClients() {
          @Override
          public ListenableFuture<Void> addMissing() {
            SettableFuture<Void> result = SettableFuture.create();
            completer.set(() -> result.set(null));
            return result;
          }
        };
    StrategyBuildResult strategyBuildResult = beginBuild();
    completer.get(2, TimeUnit.SECONDS);
    boolean cancelled = strategyBuildResult.cancelIfNotComplete(new Throwable());
    assertTrue(cancelled);

    // TODO(cjhopman): Should we cancel current uploads when cancelled?
    completer.get().run();
  }

  // TODO(cjhopman): Split this into test during execute-queue + test during execute.
  @Test
  public void testCancellationDuringExecute() throws Exception {
    SettableFuture<Runnable> completer = SettableFuture.create();

    clients =
        new SimpleRemoteExecutionClients() {
          @Override
          public ExecutionHandle execute() {
            SettableFuture<ExecutionResult> result = SettableFuture.create();
            completer.set(
                () -> {
                  try {
                    result.get();
                  } catch (InterruptedException | ExecutionException e) {
                    throw new IllegalStateException();
                  }
                });
            return new ExecutionHandle() {
              @Override
              public ListenableFuture<ExecutionResult> getResult() {
                return result;
              }

              @Override
              public ListenableFuture<ExecuteOperationMetadata> getExecutionStarted() {
                return SettableFuture.create();
              }

              @Override
              public void cancel() {
                result.setException(new IllegalAccessException());
              }
            };
          }
        };
    StrategyBuildResult strategyBuildResult = beginBuild();
    completer.get(2, TimeUnit.SECONDS);
    boolean cancelled = strategyBuildResult.cancelIfNotComplete(new Throwable());
    assertTrue(cancelled);

    // The server should have received indication that the client cancelled the call.
    expectedException.expect(IllegalStateException.class);
    completer.get().run();
  }

  @Test
  public void testCancellationDuringDownload() throws Exception {
    SettableFuture<Runnable> completer = SettableFuture.create();

    clients =
        new SimpleRemoteExecutionClients() {
          @Override
          public ListenableFuture<Void> materializeOutputs() {
            SettableFuture<Void> result = SettableFuture.create();
            completer.set(() -> result.set(null));
            return result;
          }
        };
    StrategyBuildResult strategyBuildResult = beginBuild();
    completer.get(2, TimeUnit.SECONDS);
    boolean cancelled = strategyBuildResult.cancelIfNotComplete(new Throwable());
    assertFalse(cancelled);
    assertFalse(strategyBuildResult.getBuildResult().isDone());
    completer.get().run();
    strategyBuildResult.getBuildResult().get(2, TimeUnit.SECONDS);
  }

  private static class TestRemoteExecutionConfig implements RemoteExecutionStrategyConfig {

    @Override
    public int getThreads() {
      return 1;
    }

    @Override
    public int getMaxConcurrentActionComputations() {
      return 1;
    }

    @Override
    public int getMaxConcurrentExecutions() {
      return 1;
    }

    @Override
    public int getMaxConcurrentResultHandling() {
      return 1;
    }

    @Override
    public int getMaxConcurrentPendingUploads() {
      return 1;
    }

    @Override
    public boolean isLocalFallbackEnabled() {
      return false;
    }

    @Override
    public OptionalLong maxInputSizeBytes() {
      return OptionalLong.empty();
    }

    @Override
    public String getWorkerRequirementsFilename() {
      return RemoteExecutionConfig.WORKER_REQUIREMENTS_FILENAME;
    }

    @Override
    public boolean tryLargerWorkerOnOom() {
      return false;
    }
  }

  private class SimpleRemoteExecutionClients implements RemoteExecutionClients {
    public ExecutionHandle execute() {
      return new ExecutionHandle() {
        @Override
        public ListenableFuture<ExecutionResult> getResult() {
          return Futures.immediateFuture(
              new ExecutionResult() {
                @Override
                public List<OutputDirectory> getOutputDirectories() {
                  return ImmutableList.of();
                }

                @Override
                public List<OutputFile> getOutputFiles() {
                  return ImmutableList.of(
                      protocol.newOutputFile(Paths.get("output"), missingDigest, false));
                }

                @Override
                public int getExitCode() {
                  return 0;
                }

                @Override
                public Optional<String> getStdout() {
                  return Optional.empty();
                }

                @Override
                public Optional<String> getStderr() {
                  return Optional.empty();
                }

                @Override
                public Digest getActionResultDigest() {
                  return null;
                }

                @Override
                public ExecutedActionMetadata getActionMetadata() {
                  return ExecutedActionMetadata.newBuilder().build();
                }
              });
        }

        @Override
        public ListenableFuture<ExecuteOperationMetadata> getExecutionStarted() {
          return SettableFuture.create();
        }

        @Override
        public void cancel() {}
      };
    }

    public ListenableFuture<Void> addMissing() {
      return Futures.immediateFuture(null);
    }

    public ListenableFuture<Void> materializeOutputs() {
      return Futures.immediateFuture(null);
    }

    public boolean containsDigest(Digest digest) {
      return false;
    }

    @Override
    public RemoteExecutionServiceClient getRemoteExecutionService() {
      return (actionDigest, ruleName, metadataProvider) ->
          SimpleRemoteExecutionClients.this.execute();
    }

    @Override
    public ContentAddressedStorageClient getContentAddressedStorage() {
      return new ContentAddressedStorageClient() {
        @Override
        public ListenableFuture<Void> addMissing(Collection<UploadDataSupplier> data) {
          return SimpleRemoteExecutionClients.this.addMissing();
        }

        @Override
        public ListenableFuture<Void> materializeOutputs(
            List<OutputDirectory> outputDirectories,
            List<OutputFile> outputFiles,
            FileMaterializer materializer) {
          return SimpleRemoteExecutionClients.this.materializeOutputs();
        }

        @Override
        public boolean containsDigest(Digest digest) {
          return SimpleRemoteExecutionClients.this.containsDigest(digest);
        }
      };
    }

    @Override
    public Protocol getProtocol() {
      return protocol;
    }

    @Override
    public void close() throws IOException {}
  }
}

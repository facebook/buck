/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.installer;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.facebook.buck.install.model.ErrorDetail;
import com.facebook.buck.install.model.FileReadyRequest;
import com.facebook.buck.install.model.FileResponse;
import com.facebook.buck.install.model.InstallInfoRequest;
import com.facebook.buck.install.model.InstallResponse;
import com.facebook.buck.install.model.InstallerGrpc;
import com.facebook.buck.install.model.ShutdownRequest;
import com.facebook.buck.install.model.ShutdownResponse;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD
import java.util.stream.Collectors;

/**
 * Installer Service that implements {@code install.proto}
 *
 * <p>The workflow:
 *
 * <ol>
 *   <li>client (buck2) sends `install` request with install id and file map. It could be multiple
 *       targets that would have different install id associated with them.
 *   <li>client sends multiple `fileReady` requests (file data + install id)
 *   <li>{@link InstallerService} tracks all received `fileReady` requests associated with the same
 *       install id. When all files received then {@link InstallerService} calls {@link
 *       InstallCommand} to actually install all received files for the specific install id into the
 *       device/emulator.
 *   <li>client sends `shutdownServer` when wants to stop interactions and asks {@link
 *       InstallerService} to terminate.
 * </ol>
 */
public class InstallerService extends InstallerGrpc.InstallerImplBase {

  private static final ThreadPoolExecutor THREAD_POOL =
      new ThreadPoolExecutor(
          0,
          Integer.MAX_VALUE,
          1,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          new MostExecutors.NamedThreadFactory("Installer"));

  private static final ListeningExecutorService LISTENING_EXECUTOR_SERVICE =
      MoreExecutors.listeningDecorator(THREAD_POOL);

  private final InstallCommand installer;
  private final SettableFuture<Unit> installFinished;
  private final Logger logger;
  private final Map<InstallId, Map<String, Optional<Path>>> installIdToFilesMap = new HashMap<>();

  public InstallerService(
      InstallCommand installer, SettableFuture<Unit> installFinished, Logger logger) {
    this.installer = installer;
    this.installFinished = installFinished;
    this.logger = logger;
  }

  @Override
  public void install(
      InstallInfoRequest request, StreamObserver<InstallResponse> responseObserver) {
    try {
      InstallResponse response = handleInstallRequest(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      handleException(responseObserver, e);
    }
  }

  private InstallResponse handleInstallRequest(InstallInfoRequest request) {
    InstallId installId = InstallId.of(request.getInstallId());
    Map<String, String> filesMap = request.getFilesMap();
    logger.info(
        String.format(
            "Received install id: %s to files map request info: %s",
            installId.getValue(), filesMap));
    synchronized (installIdToFilesMap) {
      installIdToFilesMap.put(
          installId,
          filesMap.keySet().stream()
              .collect(Collectors.toMap(Function.identity(), ignore -> Optional.empty())));
    }
    return InstallResponse.newBuilder().setInstallId(installId.getValue()).build();
  }

  @Override
  public void fileReady(FileReadyRequest request, StreamObserver<FileResponse> responseObserver) {
    try {
      FileResponse fileResponse = handleFileReadyRequest(request);
      responseObserver.onNext(fileResponse);
      responseObserver.onCompleted();
    } catch (Exception e) {
      handleException(responseObserver, e);
    }
  }

  private FileResponse handleFileReadyRequest(FileReadyRequest request)
      throws InterruptedException {
    InstallId installId = InstallId.of(request.getInstallId());
    String name = request.getName();
    String path = request.getPath();
    logger.info(
        String.format(
            "Received artifact %s located at %s for install id: %s",
            name, path, installId.getValue()));

    FileResponse.Builder fileResponseBuilder =
        FileResponse.newBuilder().setName(name).setPath(path).setInstallId(installId.getValue());

    ImmutableMap<String, Path> installFilesMap;
    synchronized (installIdToFilesMap) {
      Map<String, Optional<Path>> filesMap = installIdToFilesMap.get(installId);
      filesMap.put(name, Optional.of(Paths.get(path)));
      installFilesMap = getFilesMapToInstall(filesMap);
    }

    if (!installFilesMap.isEmpty()) {
      InstallResult installResult = install(installId, installFilesMap);
      if (installResult.isError()) {
        fileResponseBuilder.setErrorDetail(
            ErrorDetail.newBuilder().setMessage(installResult.getErrorMessage()).build());
      }
    }

    return fileResponseBuilder.build();
  }

  private ImmutableMap<String, Path> getFilesMapToInstall(Map<String, Optional<Path>> filesMap) {
    ImmutableMap.Builder<String, Path> installFilesMapBuilder = ImmutableMap.builder();
    boolean allFilesReceived = true;
    for (Map.Entry<String, Optional<Path>> fileEntry : filesMap.entrySet()) {
      String fileName = fileEntry.getKey();
      Optional<Path> pathOptional = fileEntry.getValue();

      if (pathOptional.isEmpty()) {
        allFilesReceived = false;
        break;
      }
      installFilesMapBuilder.put(fileName, pathOptional.get());
    }

    if (allFilesReceived) {
      return installFilesMapBuilder.build();
    }
    return ImmutableMap.of();
  }

  private InstallResult install(InstallId installId, Map<String, Path> filesMap)
      throws InterruptedException {
    logger.info(String.format("Starting install for install id: %s", installId.getValue()));

    Set<String> errorMessages = new HashSet<>();
    CountDownLatch latch = new CountDownLatch(filesMap.size());

    for (Map.Entry<String, Path> fileEntry : filesMap.entrySet()) {
      String name = fileEntry.getKey();
      Path path = fileEntry.getValue();

      ListenableFuture<InstallResult> future =
          LISTENING_EXECUTOR_SERVICE.submit(() -> install(name, path));
      Futures.addCallback(
          future, getInstallResultCallback(latch, errorMessages, name, path), directExecutor());
    }

    // wait for all install futures
    latch.await();

    if (errorMessages.isEmpty()) {
      return InstallResult.success();
    }
    return InstallResult.error(errorMessages.toString());
  }

  private FutureCallback<InstallResult> getInstallResultCallback(
      CountDownLatch latch, Set<String> errorMessages, String name, Path path) {
    return new FutureCallback<>() {
      @Override
      public void onSuccess(InstallResult installResult) {
        if (installResult.isError()) {
          String errorMessage = installResult.getErrorMessage();
          logger.info(
              String.format(
                  "Installation of file name: %s and path: %s failed with error: %s",
                  name, path, errorMessage));
          synchronized (errorMessages) {
            errorMessages.add(errorMessage);
          }
        } else {
          logger.info(
              String.format("Installation of file name: %s and path: %s finished.", name, path));
        }
        latch.countDown();
      }

      @Override
      public void onFailure(Throwable thrown) {
        String stackTraceAsString = Throwables.getStackTraceAsString(thrown);
        logger.info(
            String.format(
                "Installation of file name: %s and path: %s failed with error: %s",
                name, path, stackTraceAsString));
        synchronized (errorMessages) {
          errorMessages.add(stackTraceAsString);
        }
        latch.countDown();
      }
    };
  }

  private InstallResult install(String name, Path path) {
    logger.info(String.format("Starting install for file name: %s and path: %s", name, path));
    return installer.install(name, path);
  }

  @Override
  public void shutdownServer(
      ShutdownRequest request, StreamObserver<ShutdownResponse> responseObserver) {
    try {
      handleShutdownServerRequest(responseObserver);
    } catch (Exception e) {
      handleException(responseObserver, e);
    }
  }

  private void handleShutdownServerRequest(StreamObserver<ShutdownResponse> responseObserver) {
    logger.info("Received shutting down request");
    responseObserver.onNext(ShutdownResponse.getDefaultInstance());
    responseObserver.onCompleted();
    installFinished.set(Unit.UNIT);
  }

  private void handleException(StreamObserver<?> responseObserver, Exception e) {
    logger.log(Level.SEVERE, "Unexpected exception", e);
    responseObserver.onError(
        io.grpc.Status.INTERNAL
            .withDescription("Unexpected exception: " + Throwables.getStackTraceAsString(e))
            .asException());
  }
}

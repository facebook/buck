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

import com.facebook.buck.install.model.ErrorDetail;
import com.facebook.buck.install.model.FileReady;
import com.facebook.buck.install.model.FileResponse;
import com.facebook.buck.install.model.InstallInfo;
import com.facebook.buck.install.model.InstallResponse;
import com.facebook.buck.install.model.InstallerGrpc;
import com.facebook.buck.install.model.Shutdown;
import com.facebook.buck.install.model.ShutdownResponse;
import com.facebook.buck.util.types.Unit;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.stub.StreamObserver;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

/** Installer Service that implements {@code install.proto} */
public class InstallerService extends InstallerGrpc.InstallerImplBase {

  private final InstallCommand installer;
  private final SettableFuture<Unit> installFinished;
  private final Logger logger;
  private final Map<InstallId, Set<String>> installIdToFilesMap = new HashMap<>();

  public InstallerService(
      InstallCommand installer, SettableFuture<Unit> installFinished, Logger logger) {
    this.installer = installer;
    this.installFinished = installFinished;
    this.logger = logger;
  }

  @Override
  public void install(InstallInfo request, StreamObserver<InstallResponse> responseObserver) {
    try {
      InstallResponse response = handleInstallRequest(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      handleException(responseObserver, e);
    }
  }

  private InstallResponse handleInstallRequest(InstallInfo request) {
    InstallId installId = InstallId.of(request.getInstallId());
    Map<String, String> filesMap = request.getFilesMap();
    logger.info(
        String.format(
            "Received install id: %s to files map request info: %s",
            installId.getValue(), filesMap));
    installIdToFilesMap.put(installId, filesMap.keySet());
    return InstallResponse.newBuilder().setInstallId(installId.getValue()).build();
  }

  @Override
  public void fileReadyRequest(FileReady request, StreamObserver<FileResponse> responseObserver) {
    try {
      FileResponse fileResponse = handleFileReadyRequest(request);
      responseObserver.onNext(fileResponse);
      responseObserver.onCompleted();
    } catch (Exception e) {
      handleException(responseObserver, e);
    }
  }

  private FileResponse handleFileReadyRequest(FileReady request) {
    InstallId installId = InstallId.of(request.getInstallId());
    String name = request.getName();
    String path = request.getPath();
    logger.info(
        String.format(
            "Received artifact %s located at %s for install id: %s",
            name, path, installId.getValue()));

    logger.info("Starting install for file name: " + name);
    InstallResult installResult = installer.install(name, Paths.get(path));

    FileResponse.Builder fileResponseBuilder =
        FileResponse.newBuilder().setName(name).setPath(path).setInstallId(installId.getValue());
    if (installResult.isError()) {
      fileResponseBuilder.setErrorDetail(
          ErrorDetail.newBuilder().setMessage(installResult.getErrorMessage()).build());
    }
    return fileResponseBuilder.build();
  }

  @Override
  public void shutdownServer(Shutdown request, StreamObserver<ShutdownResponse> responseObserver) {
    try {
      handleShutdownServerRequest(request, responseObserver);
    } catch (Exception e) {
      handleException(responseObserver, e);
    }
  }

  private void handleShutdownServerRequest(
      Shutdown request, StreamObserver<ShutdownResponse> responseObserver) {
    InstallId installId = InstallId.of(request.getInstallId());
    logger.info(
        String.format("Received shutting down request for install id: %s", installId.getValue()));
    responseObserver.onNext(
        ShutdownResponse.newBuilder().setInstallId(installId.getValue()).build());
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

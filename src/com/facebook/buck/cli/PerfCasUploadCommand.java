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
package com.facebook.buck.cli;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionType;
import com.facebook.buck.remoteexecution.event.listener.RemoteExecutionEventListener;
import com.facebook.buck.remoteexecution.factory.RemoteExecutionClientsFactory;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.util.OutputsCollector;
import com.facebook.buck.remoteexecution.util.OutputsCollector.CollectedOutputs;
import com.facebook.buck.remoteexecution.util.OutputsCollector.FilesystemBackedDelegate;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.kohsuke.args4j.Option;

/** Command to measure performance of cas uploads. */
public class PerfCasUploadCommand extends AbstractCommand {
  @Option(name = "--digest-file", usage = "file to write tree digest.")
  private String digestFile;

  @Option(name = "--upload-root", usage = "directory of files to upload.")
  private String uploadRoot;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params) throws Exception {
    RemoteExecutionEventListener eventListener = new RemoteExecutionEventListener();
    params.getBuckEventBus().register(eventListener);

    RemoteExecutionConfig remoteExecutionConfig =
        params.getBuckConfig().getView(RemoteExecutionConfig.class);
    Verify.verify(
        remoteExecutionConfig.getType() == RemoteExecutionType.GRPC,
        "Expected remoteexecution.type=grpc, got %s.",
        remoteExecutionConfig.getType());
    try (RemoteExecutionClients clients =
        new RemoteExecutionClientsFactory(remoteExecutionConfig)
            .create(params.getBuckEventBus(), params.getMetadataProvider())) {
      ContentAddressedStorageClient contentAddressedStorage = clients.getContentAddressedStorage();

      Path root = Paths.get(uploadRoot);
      Verify.verify(root.isAbsolute(), "Expected upload root to be absolute, got %s.", root);
      Verify.verify(Files.exists(root), "Expected upload root %s to exist.", root);
      GrpcProtocol protocol = new GrpcProtocol();

      Path digest = Paths.get(digestFile);
      Verify.verify(digest.isAbsolute(), "Expected digest output to be absolute, got %s.", digest);
      Verify.verify(!Files.exists(digest), "Digest file %s already exists.", digest);

      printWarning(params, "Collecting files and data to be uploaded.");
      CollectedOutputs collectedOutputs =
          new OutputsCollector(protocol, new FilesystemBackedDelegate())
              .collect(ImmutableSet.of(root), root);

      Verify.verify(
          collectedOutputs.outputFiles.isEmpty(),
          "Unexpectedly got non-empty set of output files.");
      Verify.verify(
          collectedOutputs.outputDirectories.size() == 1,
          "Got unexpected amount of output directories: %d",
          collectedOutputs.outputDirectories.size());

      printWarning(params, "Beginning upload.");
      long start = System.currentTimeMillis();
      contentAddressedStorage.addMissing(collectedOutputs.requiredData).get();
      long end = System.currentTimeMillis();

      double seconds = (end - start) / 1000.0;
      double dataSize = eventListener.getCasUploadSizeBytes() / 1024.0 / 1024.0;
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.info(
                  "Uploaded %s entries, %.3fMB in %.3fs (%.3f MB/s)",
                  eventListener.getCasUploads(), dataSize, seconds, dataSize / seconds));

      OutputDirectory outputDirectory = collectedOutputs.outputDirectories.get(0);
      Digest treeDigest = outputDirectory.getTreeDigest();
      Files.write(
          digest,
          ImmutableList.of(String.format("%s %d", treeDigest.getHash(), treeDigest.getSize())));

      return ExitCode.SUCCESS;
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "measure the performance of cas uploads";
  }
}

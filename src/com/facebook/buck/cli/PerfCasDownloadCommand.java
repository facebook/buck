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
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient.FileMaterializer;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionType;
import com.facebook.buck.remoteexecution.event.listener.RemoteExecutionEventListener;
import com.facebook.buck.remoteexecution.factory.RemoteExecutionClientsFactory;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.util.OutputsMaterializer.FilesystemFileMaterializer;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.kohsuke.args4j.Option;

/** Command to measure performance of cas downloads. */
public class PerfCasDownloadCommand extends AbstractCommand {
  @Option(name = "--digest-file", usage = "file with tree digest.")
  private String digestFile;

  @Option(name = "--download-root", usage = "directory to download files into.")
  private String downloadRoot = "";

  @Option(name = "--discard-files", usage = "don't write files to disk.")
  private boolean discardFiles = false;

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

      GrpcProtocol protocol = new GrpcProtocol();

      Path digest = Paths.get(digestFile);
      Verify.verify(digest.isAbsolute(), "Expected digest input to be absolute, got %s.", digest);
      Verify.verify(Files.exists(digest), "Expected digest input %s doesn't exist.", digest);

      List<String> digestLines = Files.readAllLines(digest);
      Verify.verify(digestLines.size() == 1, "Couldn't parse digest input %s.", digest);
      String[] sections = digestLines.get(0).split(" ");
      Verify.verify(sections.length == 2, "Couldn't parse digest input %s.", digest);

      Digest treeDigest = protocol.newDigest(sections[0], Integer.parseInt(sections[1]));
      OutputDirectory outputDirectory =
          protocol.newOutputDirectory(Paths.get("__data__"), treeDigest);

      long start = System.currentTimeMillis();
      FileMaterializer materializer;
      if (discardFiles) {
        printWarning(params, "Discarding downloaded files.");
        materializer =
            new FileMaterializer() {
              @Override
              public WritableByteChannel getOutputChannel(Path path, boolean executable) {
                return new WritableByteChannel() {
                  @Override
                  public int write(ByteBuffer src) {
                    return src.remaining();
                  }

                  @Override
                  public boolean isOpen() {
                    return true;
                  }

                  @Override
                  public void close() {}
                };
              }

              @Override
              public void makeDirectories(Path dirRoot) {
                // ignored.
              }
            };
      } else {
        Path materializeRoot = Paths.get(downloadRoot);
        Verify.verify(
            materializeRoot.isAbsolute(),
            "Expected download root %s to be absolute.",
            materializeRoot);
        Verify.verify(
            Files.exists(materializeRoot),
            "Expected download root directory %s to exist.",
            downloadRoot);
        printWarning(params, "Downloading files to %s.", materializeRoot);
        materializer = new FilesystemFileMaterializer(materializeRoot);
      }

      printWarning(params, "Beginning download.");
      contentAddressedStorage
          .materializeOutputs(ImmutableList.of(outputDirectory), ImmutableList.of(), materializer)
          .get();
      long end = System.currentTimeMillis();

      double dataSize = eventListener.getCasDownloadSizeBytes() / 1024.0 / 1024.0;
      double seconds = (end - start) / 1000.0;
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.info(
                  "Downloaded %.3fMB in %.3fs (%.3f MB/s)", dataSize, seconds, dataSize / seconds));

      return ExitCode.SUCCESS;
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "measure the performance of cas downloads";
  }
}

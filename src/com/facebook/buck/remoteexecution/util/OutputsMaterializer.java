/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.remoteexecution.util;

import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient.FileMaterializer;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Used for materialzing outputs from the CAS. */
public class OutputsMaterializer {
  private final AsyncBlobFetcher fetcher;
  private final Protocol protocol;

  /** Simple default file materializer that actually materializes things on the filesystem. */
  public static class FilesystemFileMaterializer implements FileMaterializer {
    private final Path root;

    public FilesystemFileMaterializer(Path root) {
      this.root = root;
    }

    @Override
    public void makeDirectories(Path dirRoot) throws IOException {
      Files.createDirectories(root.resolve(dirRoot));
    }

    @Override
    public WritableByteChannel getOutputChannel(Path path, boolean executable) throws IOException {
      path = root.resolve(path);
      MoreFiles.createParentDirectories(path);

      FileChannel channel =
          FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
      try {
        // Creating the FileOutputStream makes the file so we can now set it executable.
        setExecutable(executable, path);
      } catch (Exception e) {
        channel.close();
        throw e;
      }
      return channel;
    }

    private void setExecutable(boolean isExecutable, Path path) {
      if (isExecutable) {
        Preconditions.checkState(path.toFile().setExecutable(true));
      }
    }
  }

  public OutputsMaterializer(AsyncBlobFetcher fetcher, Protocol protocol) {
    this.fetcher = fetcher;
    this.protocol = protocol;
  }

  /** Materialize the outputs of an action into a directory. */
  public ListenableFuture<Void> materialize(
      Collection<OutputDirectory> outputDirectories,
      Collection<Protocol.OutputFile> outputFiles,
      FileMaterializer materializer)
      throws IOException {
    ImmutableList.Builder<ListenableFuture<Void>> pending = ImmutableList.builder();

    for (Protocol.OutputFile file : outputFiles) {
      Path filePath = Paths.get(file.getPath());
      Path parent = filePath.getParent();
      if (parent != null) {
        materializer.makeDirectories(parent);
      }
      pending.add(
          fetchAndMaterialize(materializer, file.getDigest(), file.getIsExecutable(), filePath));
    }

    for (Protocol.OutputDirectory directory : outputDirectories) {
      Path dirRoot = Paths.get(directory.getPath());
      // If a directory is empty, we need to still ensure that it is created.
      materializer.makeDirectories(dirRoot);
      pending.add(
          Futures.transformAsync(
              fetcher.fetch(directory.getTreeDigest()),
              data -> {
                Protocol.Tree tree = protocol.parseTree(data);
                Map<Protocol.Digest, Protocol.Directory> childMap = new HashMap<>();
                // TODO(cjhopman): If a Tree contains multiple duplicate Directory nodes, is that
                // valid? Should that be rejected?
                for (Directory child : tree.getChildrenList()) {
                  Digest digest = protocol.computeDigest(child);
                  childMap.put(digest, child);
                }
                ImmutableList.Builder<ListenableFuture<Void>> pendingFilesBuilder =
                    ImmutableList.builder();
                materializeDirectory(
                    materializer, childMap, tree.getRoot(), dirRoot, pendingFilesBuilder::add);
                return Futures.whenAllSucceed(pendingFilesBuilder.build()).call(() -> null);
              }));
    }

    return Futures.whenAllSucceed(pending.build()).call(() -> null);
  }

  private void materializeDirectory(
      FileMaterializer materializer,
      Map<Digest, Directory> childMap,
      Directory directory,
      Path root,
      Consumer<ListenableFuture<Void>> pendingWorkConsumer)
      throws IOException {
    materializer.makeDirectories(root);
    for (Protocol.DirectoryNode childNode : directory.getDirectoriesList()) {
      materializeDirectory(
          materializer,
          childMap,
          Objects.requireNonNull(
              childMap.get(childNode.getDigest()),
              String.format("Data for dir [%s] not found in merkle-tree.", root)),
          root.resolve(childNode.getName()),
          pendingWorkConsumer);
    }

    for (Protocol.FileNode file : directory.getFilesList()) {
      pendingWorkConsumer.accept(
          fetchAndMaterialize(
              materializer,
              file.getDigest(),
              file.getIsExecutable(),
              root.resolve(file.getName())));
    }
  }

  private ListenableFuture<Void> fetchAndMaterialize(
      FileMaterializer materializer, Protocol.Digest digest, boolean isExecutable, Path path)
      throws IOException {
    WritableByteChannel channel = materializer.getOutputChannel(path, isExecutable);
    // TODO(cjhopman): This doesn't close the stream on failure.
    return Futures.transform(
        fetcher.fetchToStream(digest, channel),
        ignored -> {
          try {
            channel.close();
            return null;
          } catch (IOException e) {
            throw new UncheckedExecutionException(e);
          }
        },
        MoreExecutors.directExecutor());
  }
}

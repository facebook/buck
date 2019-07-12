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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient.FileMaterializer;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.FileNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Tree;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;
import com.google.common.io.MoreFiles;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
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
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

/** Used for materialzing outputs from the CAS. */
public class OutputsMaterializer {

  private static final Logger LOG = Logger.get(OutputsMaterializer.class);

  private final AsyncBlobFetcher fetcher;
  private final Protocol protocol;
  private final int sizeLimit;
  private final ExecutorService materializerService;
  private final BlockingDeque<PendingMaterialization> waitingMaterialization =
      new LinkedBlockingDeque<>();

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

  /** Container class for pending materialization requests */
  public static class PendingMaterialization {

    public final FileMaterializer materializer;
    public final Digest digest;
    public final boolean isExecutable;
    public final Path path;
    public final SettableFuture<Void> future;

    PendingMaterialization(
        FileMaterializer materializer,
        Digest digest,
        boolean isExecutable,
        Path path,
        SettableFuture<Void> future) {
      this.materializer = materializer;
      this.digest = digest;
      this.isExecutable = isExecutable;
      this.path = path;
      this.future = future;
    }
  }

  public OutputsMaterializer(
      int sizeLimit,
      ExecutorService materializerService,
      AsyncBlobFetcher fetcher,
      Protocol protocol) {
    this.sizeLimit = sizeLimit;
    this.fetcher = fetcher;
    this.protocol = protocol;
    this.materializerService = materializerService;
  }

  /** Materialize the outputs of an action into a directory. */
  public ListenableFuture<Void> materialize(
      Collection<OutputDirectory> outputDirectories,
      Collection<OutputFile> outputFiles,
      FileMaterializer materializer)
      throws IOException {
    ImmutableList.Builder<ListenableFuture<Void>> pending = ImmutableList.builder();

    for (OutputFile file : outputFiles) {
      Path filePath = Paths.get(file.getPath());
      Path parent = filePath.getParent();
      if (parent != null) {
        materializer.makeDirectories(parent);
      }
      SettableFuture<Void> future = SettableFuture.create();
      waitingMaterialization.add(
          new PendingMaterialization(
              materializer, file.getDigest(), file.getIsExecutable(), filePath, future));
      pending.add(future);
    }
    materializerService.submit(this::processFetchAndMaterialize);

    for (OutputDirectory directory : outputDirectories) {
      Path dirRoot = Paths.get(directory.getPath());
      // If a directory is empty, we need to still ensure that it is created.
      materializer.makeDirectories(dirRoot);
      pending.add(
          Futures.transformAsync(
              fetcher.fetch(directory.getTreeDigest()),
              data -> {
                Tree tree = protocol.parseTree(data);
                Map<Digest, Directory> childMap = new HashMap<>();
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
                return Futures.whenAllSucceed(pendingFilesBuilder.build())
                    .call(() -> null, MoreExecutors.directExecutor());
              },
              MoreExecutors.directExecutor()));
    }

    return Futures.whenAllSucceed(pending.build()).call(() -> null, MoreExecutors.directExecutor());
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

    for (FileNode file : directory.getFilesList()) {
      SettableFuture<Void> future = SettableFuture.create();
      waitingMaterialization.add(
          new PendingMaterialization(
              materializer,
              file.getDigest(),
              file.getIsExecutable(),
              root.resolve(file.getName()),
              future));
      pendingWorkConsumer.accept(future);
    }
    materializerService.submit(this::processFetchAndMaterialize);
  }

  private void processFetchAndMaterialize() {
    ImmutableList.Builder<PendingMaterialization> builder = ImmutableList.builder();
    int size = 0;
    int items = 0;
    while (!waitingMaterialization.isEmpty()) {
      PendingMaterialization data = waitingMaterialization.poll();
      if (data == null) {
        break;
      }
      if (items == 0 || (data.digest.getSize() + size < sizeLimit)) {
        builder.add(data);
        size += data.digest.getSize();
        items++;
      } else {
        waitingMaterialization.addFirst(data);
        break;
      }
    }
    ImmutableList<PendingMaterialization> pending = builder.build();

    if (!pending.isEmpty()) {
      try {
        if (size > sizeLimit) {
          LOG.debug("Starting stream request for: " + pending.size() + " requests, size: " + size);
          PendingMaterialization large = Iterables.getOnlyElement(pending);

          // Download large files as a stream
          WritableByteChannel channel =
              large.materializer.getOutputChannel(large.path, large.isExecutable);
          ListenableFuture<Void> fetchToStream = fetcher.fetchToStream(large.digest, channel);
          try {
            // Wait for the stream to finish downloading before picking up more work
            fetchToStream.get();
          } finally {
            tryCloseChannel(channel);
          }
        } else {
          LOG.debug("Starting batch request for: " + pending.size() + " items, size: " + size);
          // Download batches of small objects
          ImmutableMultimap.Builder<Digest, WritableByteChannel> digestMap =
              ImmutableMultimap.builder();
          for (PendingMaterialization p : pending) {
            WritableByteChannel channel = p.materializer.getOutputChannel(p.path, p.isExecutable);
            digestMap.put(p.digest, channel);
          }

          ImmutableMultimap<Digest, WritableByteChannel> batch = digestMap.build();
          try {
            fetcher.batchFetchBlobs(batch).get();
          } finally {
            for (WritableByteChannel channel : batch.values()) {
              tryCloseChannel(channel);
            }
          }
        }
        pending.forEach(materialization -> materialization.future.set(null));
        LOG.debug("Finished materializing: " + pending.size() + " requests, size: " + size);

      } catch (Exception e) {
        pending.forEach(materialization -> materialization.future.setException(e));
      }
    }

    if (!waitingMaterialization.isEmpty()) {
      materializerService.submit(this::processFetchAndMaterialize);
    }
  }

  private static void tryCloseChannel(WritableByteChannel channel) {
    try {
      channel.close();
    } catch (IOException e) {
      throw new UncheckedExecutionException(e);
    }
  }
}

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
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.Directory;
import com.facebook.buck.remoteexecution.Protocol.OutputDirectory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Used for materialzing outputs from the CAS. */
public class OutputsMaterializer {
  private final AsyncBlobFetcher fetcher;
  private final Protocol protocol;

  public OutputsMaterializer(AsyncBlobFetcher fetcher, Protocol protocol) {
    this.fetcher = fetcher;
    this.protocol = protocol;
  }

  private static Path ensureParent(Path path) throws IOException {
    MoreFiles.createParentDirectories(path);
    return path;
  }

  /** Materialize the outputs of an action into a directory. */
  public void materialize(
      Collection<OutputDirectory> outputDirectories,
      Collection<Protocol.OutputFile> outputFiles,
      Path root)
      throws IOException {
    ImmutableList.Builder<ListenableFuture<Void>> pending = ImmutableList.builder();

    for (Protocol.OutputFile file : outputFiles) {
      Path path = root.resolve(file.getPath());
      ensureParent(path);
      pending.add(fetchAndMaterialize(file.getDigest(), file.getIsExecutable(), path));
    }

    for (Protocol.OutputDirectory directory : outputDirectories) {
      Path dirRoot = root.resolve(directory.getPath());
      pending.add(
          Futures.transformAsync(
              fetcher.fetch(directory.getTreeDigest()),
              data -> {
                Protocol.Tree tree = protocol.parseTree(data);
                Map<Protocol.Digest, Protocol.Directory> childMap = new HashMap<>();
                // TODO(cjhopman): If a Tree contains multiple duplicate Directory nodes, is that
                // valid? Should that be rejected?
                for (Directory child : tree.getChildrenList()) {
                  childMap.put(protocol.computeDigest(child), child);
                }
                ImmutableList.Builder<ListenableFuture<Void>> pendingFilesBuilder =
                    ImmutableList.builder();
                materializeDirectory(childMap, tree.getRoot(), dirRoot, pendingFilesBuilder::add);
                return Futures.whenAllSucceed(pendingFilesBuilder.build()).call(() -> null);
              }));
    }

    try {
      Futures.whenAllSucceed(pending.build()).call(() -> null).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private void materializeDirectory(
      Map<Protocol.Digest, Protocol.Directory> childMap,
      Protocol.Directory directory,
      Path root,
      Consumer<ListenableFuture<Void>> pendingWorkConsumer)
      throws IOException {
    Files.createDirectories(root);
    for (Protocol.DirectoryNode childNode : directory.getDirectoriesList()) {

      materializeDirectory(
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
              file.getDigest(), file.getIsExecutable(), root.resolve(file.getName())));
    }
  }

  private ListenableFuture<Void> fetchAndMaterialize(
      Protocol.Digest digest, boolean isExecutable, Path path) throws IOException {
    OutputStream fileStream = new BufferedOutputStream(new FileOutputStream(path.toFile()));
    return Futures.transform(
        fetcher.fetchToStream(digest, fileStream),
        ignored -> {
          try {
            fileStream.close();
            if (isExecutable) {
              setExecutable(true, path);
            }
            return null;
          } catch (IOException e) {
            throw new UncheckedExecutionException(e);
          }
        });
  }

  private void setExecutable(boolean isExecutable, Path path) {
    if (isExecutable) {
      Preconditions.checkState(path.toFile().setExecutable(true));
    }
  }
}

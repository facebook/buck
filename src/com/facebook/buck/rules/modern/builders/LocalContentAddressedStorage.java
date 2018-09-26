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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.windowsfs.WindowsFS;
import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.CasBlobUploader;
import com.facebook.buck.remoteexecution.ContentAddressedStorage;
import com.facebook.buck.remoteexecution.MultiThreadedBlobUploader;
import com.facebook.buck.remoteexecution.MultiThreadedBlobUploader.UploadData;
import com.facebook.buck.remoteexecution.MultiThreadedBlobUploader.UploadResult;
import com.facebook.buck.remoteexecution.OutputsMaterializer;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.DirectoryNode;
import com.facebook.buck.remoteexecution.Protocol.FileNode;
import com.facebook.buck.remoteexecution.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.Protocol.SymlinkNode;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.MoreFiles;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.concurrent.KeyedLocker.AutoUnlocker;
import com.google.devtools.build.lib.concurrent.StripedKeyedLocker;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** A simple, on-disk content addressed storage. */
public class LocalContentAddressedStorage implements ContentAddressedStorage {
  private final Path cacheDir;
  private final StripedKeyedLocker<String> fileLock = new StripedKeyedLocker<>(8);

  private static final int MISSING_CHECK_LIMIT = 1000;
  private static final int UPLOAD_SIZE_LIMIT = 10 * 1024 * 1024;

  private final MultiThreadedBlobUploader uploader;
  private final OutputsMaterializer outputsMaterializer;
  private final InputsMaterializer inputsMaterializer;
  private final Protocol protocol;

  public LocalContentAddressedStorage(Path cacheDir, Protocol protocol) {
    this.cacheDir = cacheDir;
    this.protocol = protocol;
    ExecutorService uploadService = MostExecutors.newMultiThreadExecutor("local-cas-write", 4);
    this.uploader =
        new MultiThreadedBlobUploader(
            MISSING_CHECK_LIMIT,
            UPLOAD_SIZE_LIMIT,
            uploadService,
            new CasBlobUploader() {
              @Override
              public ImmutableList<UploadResult> batchUpdateBlobs(
                  ImmutableList<UploadData> blobData) {
                return LocalContentAddressedStorage.this.batchUpdateBlobs(blobData);
              }

              @Override
              public ImmutableSet<String> getMissingHashes(List<Protocol.Digest> requiredDigests) {
                return findMissing(requiredDigests)
                    .map(Protocol.Digest::getHash)
                    .collect(ImmutableSet.toImmutableSet());
              }
            });
    AsyncBlobFetcher fetcher =
        new AsyncBlobFetcher() {
          @Override
          public ListenableFuture<ByteBuffer> fetch(Protocol.Digest digest) {
            try (InputStream stream = getData(digest)) {
              return Futures.immediateFuture(ByteBuffer.wrap(ByteStreams.toByteArray(stream)));
            } catch (IOException e) {
              return Futures.immediateFailedFuture(e);
            }
          }

          @Override
          public void fetchToStream(Protocol.Digest digest, OutputStream outputStream) {
            throw new UnsupportedOperationException();
          }
        };
    this.outputsMaterializer = new OutputsMaterializer(fetcher, protocol);
    this.inputsMaterializer =
        new InputsMaterializer(
            protocol,
            new InputsMaterializer.Delegate() {
              @Override
              public void materializeFile(Path root, FileNode file) throws IOException {
                Path path = getPath(file.getDigest().getHash());
                Preconditions.checkState(Files.exists(path));
                // As this file could potentially be materialized as both executable and
                // non-executable, and
                // links share that, we need two concrete versions of the file.
                if (file.getIsExecutable()) {
                  Path exePath = path.getParent().resolve(path.getFileName() + ".x");
                  if (!Files.exists(exePath)) {
                    try (AutoUnlocker ignored = fileLock.writeLock(exePath.toString())) {
                      if (!Files.exists(exePath)) {
                        Path tempPath = path.getParent().resolve(path.getFileName() + ".x.tmp");
                        Files.copy(path, tempPath);
                        Preconditions.checkState(tempPath.toFile().setExecutable(true));
                        Files.move(tempPath, exePath);
                      }
                    }
                  }
                  path = exePath;
                }
                Path target = root.resolve(file.getName());
                Preconditions.checkState(target.normalize().startsWith(root));
                Files.createLink(target, path);
              }

              @Override
              public InputStream getData(Protocol.Digest digest) throws IOException {
                return LocalContentAddressedStorage.this.getData(digest);
              }
            });
  }

  /** Upload blobs. */
  public ImmutableList<UploadResult> batchUpdateBlobs(ImmutableList<UploadData> blobData) {
    ImmutableList.Builder<UploadResult> responseBuilder = ImmutableList.builder();
    for (UploadData data : blobData) {
      String hash = data.digest.getHash();
      try {
        Path path = ensureParent(getPath(hash));
        try (AutoUnlocker ignored = fileLock.writeLock(hash)) {
          if (Files.exists(path)) {
            continue;
          }
          Path tempPath = path.getParent().resolve(path.getFileName() + ".tmp");
          try (FileOutputStream outputStream = new FileOutputStream(tempPath.toFile());
              InputStream dataStream = data.data.get()) {
            ByteStreams.copy(dataStream, outputStream);
          }
          Files.move(tempPath, path);
        }
        responseBuilder.add(new UploadResult(data.digest, 0, null));
      } catch (IOException e) {
        responseBuilder.add(new UploadResult(data.digest, 1, e.getMessage()));
      }
    }
    return responseBuilder.build();
  }

  @Override
  public void addMissing(
      ImmutableMap<Protocol.Digest, ThrowingSupplier<InputStream, IOException>> data)
      throws IOException {
    uploader.addMissing(data);
  }

  /**
   * Materializes the outputs into the build root. All required data must be present (or inlined).
   */
  @Override
  public void materializeOutputs(
      List<OutputDirectory> outputDirectories, List<OutputFile> outputFiles, Path root)
      throws IOException {
    outputsMaterializer.materialize(outputDirectories, outputFiles, root);
  }

  public Protocol.Action materializeAction(Protocol.Digest actionDigest) throws IOException {
    return inputsMaterializer.materializeAction(actionDigest);
  }

  public Optional<Protocol.Command> materializeInputs(
      Path buildDir, Protocol.Digest rootDigest, Optional<Protocol.Digest> commandDigest)
      throws IOException {
    return inputsMaterializer.materializeInputs(buildDir, rootDigest, commandDigest);
  }

  /** Returns a list of all sub directories. */
  public ImmutableList<Protocol.Directory> getTree(Protocol.Digest rootDigest) throws IOException {
    ImmutableList.Builder<Protocol.Directory> builder = ImmutableList.builder();
    buildTree(builder::add, rootDigest);
    return builder.build();
  }

  private void buildTree(Consumer<Protocol.Directory> builder, Protocol.Digest digest)
      throws IOException {
    Protocol.Directory directory;
    try (InputStream data = getData(digest)) {
      directory = protocol.parseDirectory(ByteBuffer.wrap(ByteStreams.toByteArray(data)));
    }
    builder.accept(directory);
    for (Protocol.DirectoryNode directoryNode : directory.getDirectoriesList()) {
      buildTree(builder, directoryNode.getDigest());
    }
  }

  private static class InputsMaterializer {
    private final Protocol protocol;
    private final Delegate delegate;

    private InputsMaterializer(Protocol protocol, Delegate delegate) {
      this.protocol = protocol;
      this.delegate = delegate;
    }

    interface Delegate {
      void materializeFile(Path root, Protocol.FileNode file) throws IOException;

      InputStream getData(Protocol.Digest digest) throws IOException;

      default void materializeSymlink(Path root, SymlinkNode symlink) throws IOException {
        MorePaths.createSymLink(
            new WindowsFS(), root.resolve(symlink.getName()), Paths.get(symlink.getTarget()));
      }
    }

    /** Materializes all of the inputs into root. All required data must be present. */
    public Optional<Protocol.Command> materializeInputs(
        Path root, Protocol.Digest inputsDigest, Optional<Protocol.Digest> commandDigest)
        throws IOException {
      Protocol.Directory dir;
      try (InputStream dataStream = delegate.getData(inputsDigest)) {
        dir = protocol.parseDirectory(ByteBuffer.wrap(ByteStreams.toByteArray(dataStream)));
      }

      Files.createDirectories(root);
      for (FileNode file : dir.getFilesList()) {
        delegate.materializeFile(root, file);
      }
      for (DirectoryNode child : dir.getDirectoriesList()) {
        materializeInputs(root.resolve(child.getName()), child.getDigest(), commandDigest);
      }
      for (SymlinkNode symlink : dir.getSymlinksList()) {
        delegate.materializeSymlink(root, symlink);
      }

      if (commandDigest.isPresent()) {
        try (InputStream dataStream = delegate.getData(commandDigest.get())) {
          return Optional.of(
              protocol.parseCommand(ByteBuffer.wrap(ByteStreams.toByteArray(dataStream))));
        }
      }
      return Optional.empty();
    }

    public Protocol.Action materializeAction(Protocol.Digest actionDigest) throws IOException {
      try (InputStream dataStream = delegate.getData(actionDigest)) {
        return protocol.parseAction(ByteBuffer.wrap(ByteStreams.toByteArray(dataStream)));
      }
    }
  }

  /** Looks up some data. Used internally and in tests. */
  @VisibleForTesting
  public InputStream getData(Protocol.Digest digest) throws IOException {
    Path path = getPath(digest.getHash());
    Preconditions.checkState(Files.exists(path), "Couldn't find %s.", path);
    return new FileInputStream(path.toFile());
  }

  private static Path ensureParent(Path path) throws IOException {
    MoreFiles.createParentDirectories(path);
    return path;
  }

  private Path getPath(String hashString) {
    return cacheDir
        .resolve(hashString.substring(0, 2))
        .resolve(hashString.substring(2, 4))
        .resolve(hashString);
  }

  public Stream<Protocol.Digest> findMissing(Iterable<Protocol.Digest> digests) {
    return RichStream.from(digests).filter(digest -> !Files.exists(getPath(digest.getHash())));
  }
}

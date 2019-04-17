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
package com.facebook.buck.remoteexecution.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.remoteexecution.AsyncBlobFetcher;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient.FileMaterializer;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.util.OutputsCollector.CollectedOutputs;
import com.facebook.buck.remoteexecution.util.OutputsCollector.Delegate;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.Test;

public class OutputsMaterializerTest {

  @Test
  public void testMaterializeFiles() throws IOException, ExecutionException, InterruptedException {
    Protocol protocol = new GrpcProtocol();
    OutputsMaterializerTest.RecordingFileMaterializer recordingMaterializer =
        new RecordingFileMaterializer();

    Path path1 = Paths.get("some/output/one");
    Path path2 = Paths.get("some/output/two");
    Path path3 = Paths.get("some/other/output/three");
    Path path4 = Paths.get("some/final/output/four");

    ByteString data1 = ByteString.copyFromUtf8("data1");
    ByteString data2 = ByteString.copyFromUtf8("data2");
    ByteString data3 = ByteString.copyFromUtf8("data3");
    ByteString data4 = ByteString.copyFromUtf8("data4");

    Digest digest1 = protocol.computeDigest(data1.toByteArray());
    Digest digest2 = protocol.computeDigest(data2.toByteArray());
    Digest digest3 = protocol.computeDigest(data3.toByteArray());
    Digest digest4 = protocol.computeDigest(data4.toByteArray());

    OutputFile outputFile1 = protocol.newOutputFile(path1, digest1, false);
    OutputFile outputFile2 = protocol.newOutputFile(path2, digest2, false);
    OutputFile outputFile3 = protocol.newOutputFile(path3, digest3, true);
    OutputFile outputFile4 = protocol.newOutputFile(path4, digest4, false);

    AsyncBlobFetcher fetcher =
        new SimpleSingleThreadedBlobFetcher(
            ImmutableMap.of(digest1, data1, digest2, data2, digest3, data3, digest4, data4));

    new OutputsMaterializer(fetcher, protocol)
        .materialize(
            ImmutableList.of(),
            ImmutableList.of(outputFile1, outputFile2, outputFile3, outputFile4),
            recordingMaterializer)
        .get();

    Map<Path, OutputItemState> expectedState =
        ImmutableMap.of(
            path1,
            new OutputItemState(data1, false),
            path2,
            new OutputItemState(data2, false),
            path3,
            new OutputItemState(data3, true),
            path4,
            new OutputItemState(data4, false));

    recordingMaterializer.verify(
        expectedState,
        ImmutableSet.of(
            "some",
            "some/output",
            "some/other",
            "some/other/output",
            "some/final",
            "some/final/output"));
  }

  @Test
  public void testMaterializeDirs() throws IOException, ExecutionException, InterruptedException {
    Protocol protocol = new GrpcProtocol();
    OutputsMaterializerTest.RecordingFileMaterializer recordingMaterializer =
        new RecordingFileMaterializer();

    Path path1 = Paths.get("root/some/output/one");
    Path path2 = Paths.get("root/some/output/two");
    Path path3 = Paths.get("root/other/output/three");

    ByteString data1 = ByteString.copyFromUtf8("data1");
    ByteString data2 = ByteString.copyFromUtf8("data2");
    ByteString data3 = ByteString.copyFromUtf8("data3");

    Path rootDir = Paths.get("root");
    CollectedOutputs collectedOutputs =
        createCollectedOutputs(
            ImmutableMap.of(path1, data1, path2, data2, path3, data3),
            ImmutableSet.of(Paths.get("root/some/output"), Paths.get("root/other")),
            rootDir,
            protocol);

    AsyncBlobFetcher fetcher =
        new SimpleSingleThreadedBlobFetcher(
            collectedOutputs.requiredData.stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        data -> data.getDigest(),
                        data -> {
                          try (InputStream stream = data.get()) {
                            return ByteString.readFrom(stream);
                          } catch (IOException e) {
                            throw new RuntimeException(e);
                          }
                        })));

    new OutputsMaterializer(fetcher, protocol)
        .materialize(
            collectedOutputs.outputDirectories, collectedOutputs.outputFiles, recordingMaterializer)
        .get();

    Map<Path, OutputItemState> expectedState =
        ImmutableMap.of(
            rootDir.relativize(path1),
            new OutputItemState(data1, false),
            rootDir.relativize(path2),
            new OutputItemState(data2, false),
            rootDir.relativize(path3),
            new OutputItemState(data3, false));

    recordingMaterializer.verify(
        expectedState, ImmutableSet.of("some", "some/output", "other", "other/output"));
  }

  public CollectedOutputs createCollectedOutputs(
      Map<Path, ByteString> data, Set<Path> outputs, Path rootDir, Protocol protocol)
      throws IOException {
    Set<Path> dirs = new HashSet<>();
    dirs.add(rootDir);

    data.keySet()
        .forEach(
            path -> {
              Verify.verify(path.startsWith(rootDir));
              path = path.getParent();
              while (path.startsWith(rootDir)) {
                if (!dirs.add(path)) {
                  break;
                }
                path = path.getParent();
              }
            });

    return new OutputsCollector(
            protocol,
            new Delegate() {
              @Override
              public ByteSource asByteSource(Path file) {
                Verify.verify(data.containsKey(file), "Couldn't find file %s.", file);
                return ByteSource.wrap(data.get(file).toByteArray());
              }

              @Override
              public boolean exists(Path path) {
                return data.containsKey(path) || dirs.contains(path);
              }

              @Override
              public boolean isDirectory(Path path) {
                return dirs.contains(path);
              }

              @Override
              public Stream<Path> walk(Path path) {
                return data.keySet().stream().filter(candidate -> candidate.startsWith(path));
              }

              @Override
              public boolean isRegularFile(Path entry) {
                // TODO(cjhopman): support this if needed.
                return data.containsKey(entry);
              }

              @Override
              public boolean isExecutable(Path entry) {
                // TODO(cjhopman): support executables.
                return false;
              }

              @Override
              public InputStream getInputStream(Path path) throws IOException {
                return asByteSource(path).openStream();
              }

              @Override
              public long size(Path path) throws IOException {
                return asByteSource(path).size();
              }
            })
        .collect(
            outputs.stream().map(rootDir::relativize).collect(ImmutableSet.toImmutableSet()),
            rootDir);
  }

  private static class OutputItemState {
    private final boolean executable;
    boolean isClosed = false;
    ByteString data = ByteString.EMPTY;

    public OutputItemState(boolean executable) {
      this.executable = executable;
    }

    public OutputItemState(ByteString data, boolean executable) {
      this.executable = executable;
      this.isClosed = true;
      this.data = data;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof OutputItemState)) {
        return false;
      }
      OutputItemState other = ((OutputItemState) obj);
      return other.executable == executable
          && other.isClosed == isClosed
          && other.data.toStringUtf8().equals(data.toStringUtf8());
    }

    @Override
    public int hashCode() {
      return Objects.hash(executable, isClosed, data);
    }

    @Override
    public String toString() {
      return "OutputItemState{"
          + "executable="
          + executable
          + ", isClosed="
          + isClosed
          + ", data="
          + data.toStringUtf8()
          + '}';
    }
  }

  private static class RecordingFileMaterializer implements FileMaterializer {
    private Map<Path, OutputItemState> outputs = new ConcurrentHashMap<>();
    private Set<Path> dirs = Sets.newConcurrentHashSet();

    @Override
    public WritableByteChannel getOutputChannel(Path path, boolean executable) {
      Path parent = path.getParent();
      assertTrue(parent == null || dirs.contains(parent));
      assertFalse(dirs.contains(path));
      OutputItemState state = new OutputItemState(executable);
      assertNull(outputs.putIfAbsent(path, state));
      return new WritableByteChannel() {
        @Override
        public boolean isOpen() {
          return !state.isClosed;
        }

        @Override
        public void close() {
          state.isClosed = true;
        }

        @Override
        public int write(ByteBuffer src) {
          int remaining = src.remaining();
          state.data = state.data.concat(ByteString.copyFrom(src));
          return remaining;
        }
      };
    }

    @Override
    public void makeDirectories(Path dirRoot) {
      if (dirRoot == null) {
        return;
      }
      dirs.add(dirRoot);
      makeDirectories(dirRoot.getParent());
    }

    public void verify(Map<Path, OutputItemState> expectedState, Set<String> expectedDirs) {
      // Make sorted copies here so we don't need to worry about it elsewhere.
      assertEquals(ImmutableSortedMap.copyOf(expectedState), ImmutableSortedMap.copyOf(outputs));
      assertEquals(
          expectedDirs.stream()
              .map(Paths::get)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
          ImmutableSortedSet.copyOf(dirs));
    }
  }

  private static class SimpleSingleThreadedBlobFetcher implements AsyncBlobFetcher {
    private final ListeningExecutorService fetcherService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    private final Map<Digest, ByteString> data;

    public SimpleSingleThreadedBlobFetcher(Map<Digest, ByteString> data) {
      this.data = data;
    }

    @Override
    public ListenableFuture<ByteBuffer> fetch(Digest digest) {
      return fetcherService.submit(() -> ByteBuffer.wrap(data.get(digest).toByteArray()));
    }

    @Override
    public ListenableFuture<Void> fetchToStream(Digest digest, WritableByteChannel channel) {
      return Futures.transform(
          fetch(digest),
          buf -> {
            try {
              channel.write(buf);
              return null;
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }
  }
}

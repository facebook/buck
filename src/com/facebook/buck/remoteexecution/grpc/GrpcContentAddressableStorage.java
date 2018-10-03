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

package com.facebook.buck.remoteexecution.grpc;

import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageFutureStub;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.remoteexecution.ContentAddressedStorage;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.Protocol.OutputFile;
import com.facebook.buck.remoteexecution.util.MultiThreadedBlobUploader;
import com.facebook.buck.remoteexecution.util.OutputsMaterializer;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** Implementation of CAS using GRPC. */
public class GrpcContentAddressableStorage implements ContentAddressedStorage {
  private final MultiThreadedBlobUploader uploader;
  private final OutputsMaterializer outputsMaterializer;

  public GrpcContentAddressableStorage(
      ContentAddressableStorageFutureStub storageStub,
      ByteStreamStub byteStreamStub,
      String instanceName,
      Protocol protocol,
      BuckEventBus buckEventBus) {
    this.uploader =
        new MultiThreadedBlobUploader(
            1000,
            10 * 1024 * 1024,
            MostExecutors.newMultiThreadExecutor("blob-uploader", 4),
            new GrpcCasBlobUploader(storageStub, buckEventBus));

    this.outputsMaterializer =
        new OutputsMaterializer(
            new GrpcAsyncBlobFetcher(instanceName, byteStreamStub, buckEventBus), protocol);
  }

  @Override
  public void addMissing(
      ImmutableMap<Protocol.Digest, ThrowingSupplier<InputStream, IOException>> data)
      throws IOException {
    uploader.addMissing(data);
  }

  @Override
  public void materializeOutputs(
      List<OutputDirectory> outputDirectories, List<OutputFile> outputFiles, Path root)
      throws IOException {
    outputsMaterializer.materialize(outputDirectories, outputFiles, root);
  }
}

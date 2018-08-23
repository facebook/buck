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

package com.facebook.buck.rules.modern.builders.thrift.cas;

import com.facebook.buck.rules.modern.builders.ContentAddressedStorage;
import com.facebook.buck.rules.modern.builders.MultiThreadedBlobUploader;
import com.facebook.buck.rules.modern.builders.OutputsMaterializer;
import com.facebook.buck.rules.modern.builders.Protocol;
import com.facebook.buck.rules.modern.builders.thrift.ThriftAsyncClientFactory;
import com.facebook.buck.rules.modern.builders.thrift.ThriftProtocol;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.remoteexecution.cas.ContentAddressableStorage;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** A Thrift-based content addressable storage implementation. */
public class ThriftContentAddressedStorage implements ContentAddressedStorage {

  private static final Protocol PROTOCOL = new ThriftProtocol(); // TODO: set to correct protocol
  private final MultiThreadedBlobUploader uploader;
  private final OutputsMaterializer materializer;

  public ThriftContentAddressedStorage(
      ContentAddressableStorage.Client client,
      ThriftAsyncClientFactory<ContentAddressableStorage.AsyncClient> asyncClientFactory) {
    uploader =
        new MultiThreadedBlobUploader(
            1000,
            10 * 1024 * 1024,
            MostExecutors.newMultiThreadExecutor("blob-uploader", 4),
            new ThriftCasBlobUploader(client));

    materializer =
        new OutputsMaterializer(new ThriftAsyncBlobFetcher(asyncClientFactory), PROTOCOL);
  }

  @Override
  public void addMissing(
      ImmutableMap<Protocol.Digest, ThrowingSupplier<InputStream, IOException>> data)
      throws IOException {
    uploader.addMissing(data);
  }

  @Override
  public void materializeOutputs(
      List<Protocol.OutputDirectory> outputDirectories,
      List<Protocol.OutputFile> outputFiles,
      Path root)
      throws IOException {
    materializer.materialize(outputDirectories, outputFiles, root);
  }
}

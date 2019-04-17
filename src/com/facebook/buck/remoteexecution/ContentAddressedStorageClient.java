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

package com.facebook.buck.remoteexecution;

import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** This is a simple ContentAddressedStorageClient interface used for remote execution. */
public interface ContentAddressedStorageClient {
  ListenableFuture<Void> addMissing(Collection<UploadDataSupplier> data) throws IOException;

  /** Materializes the outputFiles and outputDirectories into root. */
  ListenableFuture<Void> materializeOutputs(
      List<OutputDirectory> outputDirectories,
      List<OutputFile> outputFiles,
      FileMaterializer materializer)
      throws IOException;

  boolean containsDigest(Digest digest);

  /** Interface for filesystem operations required for materialization. */
  interface FileMaterializer {
    /**
     * Get a writable channel to the file at the provided path, marking it executable if
     * appropriate.
     */
    WritableByteChannel getOutputChannel(Path path, boolean executable) throws IOException;

    /** Make the directory and all parent directories. */
    void makeDirectories(Path dirRoot) throws IOException;
  }
}

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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;

/** Interface used to upload inputs/outputs to the CAS. */
public interface CasBlobUploader {
  ImmutableSet<String> getMissingHashes(List<Digest> requiredDigests) throws IOException;

  ImmutableList<UploadResult> batchUpdateBlobs(ImmutableList<UploadDataSupplier> build)
      throws IOException;

  /** Result (status/error message) of an upload. */
  class UploadResult {
    public final Digest digest;
    public final int status;
    @Nullable public final String message;

    public UploadResult(Digest digest, int status, @Nullable String message) {
      this.digest = digest;
      this.status = status;
      this.message = message;
    }
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.network;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Implemented by classes providing the functionality to upload log data to remote entities.
 */
public interface RemoteLogger {
  /**
   * @param jsonBlob data to upload.
   * @return {@link Optional#absent()} if the data has merely been buffered, a
   *         {@link ListenableFuture} representing the upload otherwise.
   */
  Optional<ListenableFuture<Void>> log(String jsonBlob);

  /**
   * If the underlying logger employs buffering this signals it to upload whatever remaining
   * information it had stored.
   *
   * @return future representing the forced upload.
   */
  ListenableFuture<Void> close();
}

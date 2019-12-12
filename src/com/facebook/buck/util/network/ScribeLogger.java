/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.network;

import com.facebook.buck.util.types.Unit;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.Closeable;
import java.util.Optional;

public abstract class ScribeLogger implements Closeable {
  public ListenableFuture<Unit> log(String category, Iterable<String> lines) {
    return log(category, lines, Optional.empty());
  }

  public abstract ListenableFuture<Unit> log(
      String category, Iterable<String> lines, Optional<Integer> bucket);
}

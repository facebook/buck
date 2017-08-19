/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.facebook.buck.model.Either;
import com.facebook.buck.rules.SourcePath;
import com.google.common.annotations.VisibleForTesting;
import java.util.Optional;

// TODO(cjhopman): make it so that an InputData constructed from an OutputData gets it's data
// through the OutputData rather than through a SourcePath.
public final class InputData {
  private Either<SourcePath, String> data;

  @VisibleForTesting
  public InputData(SourcePath path) {
    this.data = Either.ofLeft(path);
  }

  @VisibleForTesting
  public InputData(String data) {
    this.data = Either.ofRight(data);
  }

  public Optional<SourcePath> getSourcePath() {
    return data.isLeft() ? Optional.of(data.getLeft()) : Optional.empty();
  }

  public Object getRuleKeyObject() {
    return data;
  }
}

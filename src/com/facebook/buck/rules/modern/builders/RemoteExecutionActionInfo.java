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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** This includes all the information needed to run a remote execution command. */
@BuckStyleValue
public abstract class RemoteExecutionActionInfo {
  public abstract Digest getActionDigest();

  public abstract ImmutableList<UploadDataSupplier> getRequiredData();

  public abstract long getTotalInputSize();

  public abstract Iterable<? extends Path> getOutputs();

  public RemoteExecutionActionInfo withRequiredData(ImmutableList<UploadDataSupplier> data) {
    return of(getActionDigest(), data, getTotalInputSize(), getOutputs());
  }

  public static RemoteExecutionActionInfo of(
      Protocol.Digest actionDigest,
      ImmutableList<UploadDataSupplier> requiredData,
      long totalInputSize,
      java.lang.Iterable<? extends Path> outputs) {
    return ImmutableRemoteExecutionActionInfo.of(
        actionDigest, requiredData, totalInputSize, outputs);
  }
}

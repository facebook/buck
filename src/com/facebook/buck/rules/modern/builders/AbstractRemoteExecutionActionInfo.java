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

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import org.immutables.value.Value;

/** This includes all the information needed to run a remote execution command. */
@Value.Immutable
@BuckStyleTuple
interface AbstractRemoteExecutionActionInfo {
  Digest getActionDigest();

  ImmutableMap<Digest, UploadDataSupplier> getRequiredData();

  Iterable<? extends Path> getOutputs();
}

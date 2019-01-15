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

import com.facebook.buck.log.TraceInfoProvider;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.remoteexecution.proto.TraceInfo;

/** Static class providing factory methods for instances of MetadataProviders. */
public class MetadataProviderFactory {
  private MetadataProviderFactory() {
    // static class.
  }

  /** @return Metadata provider that always returns empty Metadata. */
  public static MetadataProvider emptyMetadataProvider() {
    return new MetadataProvider() {
      @Override
      public RemoteExecutionMetadata get() {
        return RemoteExecutionMetadata.newBuilder().build();
      }

      @Override
      public RemoteExecutionMetadata getForAction(String actionDigest) {
        return get();
      }
    };
  }

  /** Wraps the argument MetadataProvider return value with info about tracing. */
  public static MetadataProvider wrapWithTraceInfo(
      MetadataProvider metadataProvider, final TraceInfoProvider traceInfoProvider) {
    return new MetadataProvider() {
      @Override
      public RemoteExecutionMetadata get() {
        TraceInfo traceInfo =
            TraceInfo.newBuilder().setTraceId(traceInfoProvider.getTraceId()).build();
        return metadataProvider.get().toBuilder().setTraceInfo(traceInfo).build();
      }

      @Override
      public RemoteExecutionMetadata getForAction(String actionDigest) {
        TraceInfo traceInfo =
            TraceInfo.newBuilder()
                .setTraceId(traceInfoProvider.getTraceId())
                .setEdgeId(traceInfoProvider.getEdgeId(actionDigest))
                .build();
        return metadataProvider.get().toBuilder().setTraceInfo(traceInfo).build();
      }
    };
  }
}

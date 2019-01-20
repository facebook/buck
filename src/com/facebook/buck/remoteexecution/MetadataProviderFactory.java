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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.log.TraceInfoProvider;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.proto.BuckInfo;
import com.facebook.buck.remoteexecution.proto.CreatorInfo;
import com.facebook.buck.remoteexecution.proto.RESessionID;
import com.facebook.buck.remoteexecution.proto.RemoteExecutionMetadata;
import com.facebook.buck.remoteexecution.proto.TraceInfo;
import java.util.UUID;

/** Static class providing factory methods for instances of MetadataProviders. */
public class MetadataProviderFactory {

  private static final String DEFAULT_CLIENT_TYPE = "buck";
  private static final String RE_SESSION_ID_PREFIX = "reSessionID-";

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

  /**
   * @return Metadata provider that provides minimal amount information that should be passed along
   *     remote execution requests.
   */
  public static MetadataProvider minimalMetadataProviderForBuild(BuildId buildId, String username) {
    return new MetadataProvider() {
      final RemoteExecutionMetadata metadata;

      {
        // TODO(msienkiewicz): Allow overriding RE Session ID, client type, username with config
        // flags/env vars.
        String reSessionIDRaw = RE_SESSION_ID_PREFIX + UUID.randomUUID();
        RESessionID reSessionID = RESessionID.newBuilder().setId(reSessionIDRaw).build();
        BuckInfo buckInfo = BuckInfo.newBuilder().setBuildId(buildId.toString()).build();
        CreatorInfo creatorInfo =
            CreatorInfo.newBuilder()
                .setClientType(DEFAULT_CLIENT_TYPE)
                .setUsername(username)
                .build();
        metadata =
            RemoteExecutionMetadata.newBuilder()
                .setReSessionId(reSessionID)
                .setBuckInfo(buckInfo)
                .setCreatorInfo(creatorInfo)
                .build();
      }

      @Override
      public RemoteExecutionMetadata get() {
        return metadata;
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

/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.Configs;
import com.facebook.buck.config.RawConfig;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateBuckConfig;
import com.facebook.buck.distributed.thrift.OrderedStringMapEntry;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class DistributedBuildState {

  private final BuildJobState remoteState;

  DistributedBuildState(BuildJobState state) {
    this.remoteState = state;
  }

  public static BuildJobState dump(BuckConfig buckConfig) {
    BuildJobState jobState = new BuildJobState();
    jobState.setBuckConfig(dumpConfig(buckConfig));
    return jobState;
  }

  private static BuildJobStateBuckConfig dumpConfig(BuckConfig buckConfig) {
    BuildJobStateBuckConfig jobState = new BuildJobStateBuckConfig();

    jobState.setUserEnvironment(buckConfig.getEnvironment());
    Map<String, List<OrderedStringMapEntry>> rawConfig = Maps.transformValues(
        buckConfig.getRawConfigForDistBuild(),
        new Function<ImmutableMap<String, String>, List<OrderedStringMapEntry>>() {
          @Override
          public List<OrderedStringMapEntry> apply(ImmutableMap<String, String> input) {
            List<OrderedStringMapEntry> result = new ArrayList<>();
            for (Map.Entry<String, String> entry : input.entrySet()) {
              result.add(new OrderedStringMapEntry(entry.getKey(), entry.getValue()));
            }
            return result;
          }
        });
    jobState.setRawBuckConfig(rawConfig);
    jobState.setArchitecture(buckConfig.getArchitecture().name());
    jobState.setPlatform(buckConfig.getPlatform().name());

    return jobState;
  }

  public static DistributedBuildState load(TProtocol protocol) throws TException {
    BuildJobState jobState = new BuildJobState();
    jobState.read(protocol);
    return new DistributedBuildState(jobState);
  }

  public BuckConfig createBuckConfig(ProjectFilesystem projectFilesystem) {
    BuildJobStateBuckConfig remoteBuckConfig = remoteState.getBuckConfig();

    ImmutableMap<String, ImmutableMap<String, String>> rawConfig = ImmutableMap.copyOf(
        Maps.transformValues(
            remoteBuckConfig.getRawBuckConfig(),
            new Function<List<OrderedStringMapEntry>, ImmutableMap<String, String>>() {
              @Override
              public ImmutableMap<String, String> apply(List<OrderedStringMapEntry> input) {
                ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
                for (OrderedStringMapEntry entry : input) {
                  builder.put(entry.getKey(), entry.getValue());
                }
                return builder.build();
              }
            }));

    Architecture remoteArchitecture = Architecture.valueOf(remoteBuckConfig.getArchitecture());
    Architecture localArchitecture = Architecture.detect();
    Preconditions.checkState(
        remoteArchitecture.equals(localArchitecture),
        "Trying to load config with architecture %s on a machine that is %s. " +
            "This is not supported.",
        remoteArchitecture,
        localArchitecture);

    Platform remotePlatform = Platform.valueOf(remoteBuckConfig.getPlatform());
    Platform localPlatform = Platform.detect();
    Preconditions.checkState(
        remotePlatform.equals(localPlatform),
        "Trying to load config with platform %s on a machine that is %s. This is not supported.",
        remotePlatform,
        localPlatform);

    return new BuckConfig(
        Configs.createConfig(RawConfig.of(rawConfig)),
        projectFilesystem,
        remoteArchitecture,
        remotePlatform,
        ImmutableMap.copyOf(remoteBuckConfig.getUserEnvironment()));
  }
}

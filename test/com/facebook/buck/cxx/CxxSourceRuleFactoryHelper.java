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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;

import java.nio.file.Path;

public class CxxSourceRuleFactoryHelper {

  private CxxSourceRuleFactoryHelper() {}

  public static CxxSourceRuleFactory of(
      Path cellRoot,
      BuildTarget target,
      CxxPlatform cxxPlatform) {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    return CxxSourceRuleFactory.builder()
        .setParams(new FakeBuildRuleParamsBuilder(target)
                .setProjectFilesystem(new FakeProjectFilesystem(cellRoot.toFile()))
                .build())
        .setResolver(resolver)
        .setPathResolver(new SourcePathResolver(resolver))
        .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
        .setCxxPlatform(cxxPlatform)
        .setPicType(CxxSourceRuleFactory.PicType.PDC)
        .build();
  }

  public static CxxSourceRuleFactory of(
      Path cellRoot,
      BuildTarget target,
      CxxPlatform cxxPlatform,
      CxxSourceRuleFactory.PicType picType) {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    return CxxSourceRuleFactory.builder()
        .setParams(new FakeBuildRuleParamsBuilder(target)
            .setProjectFilesystem(new FakeProjectFilesystem(cellRoot.toFile()))
            .build())
        .setResolver(resolver)
        .setPathResolver(new SourcePathResolver(resolver))
        .setCxxBuckConfig(CxxPlatformUtils.DEFAULT_CONFIG)
        .setCxxPlatform(cxxPlatform)
        .setPicType(picType)
        .build();
  }

}

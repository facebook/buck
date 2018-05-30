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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import java.nio.file.Path;

public class CxxSourceRuleFactoryHelper {

  private CxxSourceRuleFactoryHelper() {}

  public static CxxSourceRuleFactory of(
      Path cellRoot, BuildTarget target, CxxPlatform cxxPlatform) {
    return of(cellRoot, target, cxxPlatform, CxxPlatformUtils.DEFAULT_CONFIG);
  }

  public static CxxSourceRuleFactory of(
      Path cellRoot, BuildTarget target, CxxPlatform cxxPlatform, CxxBuckConfig cxxBuckConfig) {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxSourceRuleFactory.builder()
        .setProjectFilesystem(new FakeProjectFilesystem(cellRoot))
        .setBaseBuildTarget(target)
        .setActionGraphBuilder(graphBuilder)
        .setPathResolver(pathResolver)
        .setRuleFinder(ruleFinder)
        .setCxxBuckConfig(cxxBuckConfig)
        .setCxxPlatform(cxxPlatform)
        .setPicType(PicType.PDC)
        .build();
  }

  public static CxxSourceRuleFactory of(
      Path cellRoot, BuildTarget target, CxxPlatform cxxPlatform, PicType picType) {
    return of(cellRoot, target, cxxPlatform, CxxPlatformUtils.DEFAULT_CONFIG, picType);
  }

  public static CxxSourceRuleFactory of(
      Path cellRoot,
      BuildTarget target,
      CxxPlatform cxxPlatform,
      CxxBuckConfig cxxBuckConfig,
      PicType picType) {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return CxxSourceRuleFactory.builder()
        .setBaseBuildTarget(target)
        .setProjectFilesystem(new FakeProjectFilesystem(cellRoot))
        .setActionGraphBuilder(graphBuilder)
        .setPathResolver(pathResolver)
        .setRuleFinder(ruleFinder)
        .setCxxBuckConfig(cxxBuckConfig)
        .setCxxPlatform(cxxPlatform)
        .setPicType(picType)
        .build();
  }
}

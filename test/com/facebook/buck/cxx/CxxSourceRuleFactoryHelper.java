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

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import java.nio.file.Path;
import java.util.Optional;

public class CxxSourceRuleFactoryHelper {

  private CxxSourceRuleFactoryHelper() {}

  public static CxxSourceRuleFactory of(
      Path cellRoot, BuildTarget target, CxxPlatform cxxPlatform) {
    return of(cellRoot, target, cxxPlatform, CxxPlatformUtils.DEFAULT_CONFIG);
  }

  public static CxxSourceRuleFactory of(
      Path cellRoot, BuildTarget target, CxxPlatform cxxPlatform, CxxBuckConfig cxxBuckConfig) {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    return CxxSourceRuleFactory.of(
        new FakeProjectFilesystem(CanonicalCellName.rootCell(), cellRoot),
        target,
        graphBuilder,
        graphBuilder.getSourcePathResolver(),
        cxxBuckConfig,
        cxxPlatform,
        ImmutableList.of(),
        ImmutableMultimap.of(),
        Optional.empty(),
        Optional.empty(),
        PicType.PDC);
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
    return CxxSourceRuleFactory.of(
        new FakeProjectFilesystem(CanonicalCellName.rootCell(), cellRoot),
        target,
        graphBuilder,
        graphBuilder.getSourcePathResolver(),
        cxxBuckConfig,
        cxxPlatform,
        ImmutableList.of(),
        ImmutableMultimap.of(),
        Optional.empty(),
        Optional.empty(),
        picType);
  }
}

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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;
import org.junit.Test;

public class PythonUtilTest {

  @Test
  public void toModuleMapWithExplicitMap() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ImmutableMap.Builder<Path, SourcePath> srcsBuilder = ImmutableMap.builder();
    PythonUtil.forEachModuleParam(
        target,
        new TestActionGraphBuilder(),
        CxxPlatformUtils.DEFAULT_PLATFORM,
        "srcs",
        target.getCellRelativeBasePath().getPath().toPathDefaultFileSystem(),
        ImmutableList.of(
            SourceSortedSet.ofNamedSources(
                ImmutableSortedMap.of("hello.py", FakeSourcePath.of("goodbye.py")))),
        srcsBuilder::put);
    ImmutableMap<Path, SourcePath> srcs = srcsBuilder.build();
    assertEquals(
        ImmutableMap.<Path, SourcePath>of(
            target
                .getCellRelativeBasePath()
                .getPath()
                .toPathDefaultFileSystem()
                .resolve("hello.py"),
            FakeSourcePath.of("goodbye.py")),
        srcs);
  }
}

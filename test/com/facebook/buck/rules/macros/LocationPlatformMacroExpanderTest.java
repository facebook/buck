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

package com.facebook.buck.rules.macros;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class LocationPlatformMacroExpanderTest {

  @Test
  public void expandCxxLibrary() throws Exception {
    CxxLibraryBuilder builder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("foo.cpp"))));
    TargetNode<?> node = builder.build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(node);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();
    CxxLink lib =
        (CxxLink)
            graphBuilder.requireRule(
                node.getBuildTarget()
                    .withAppendedFlavors(
                        CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR,
                        CxxDescriptionEnhancer.SHARED_FLAVOR));
    LocationPlatformMacroExpander expander =
        new LocationPlatformMacroExpander(CxxPlatformUtils.DEFAULT_PLATFORM);
    String expanded =
        Arg.stringify(
            expander.expandFrom(
                node.getBuildTarget(),
                graphBuilder,
                LocationPlatformMacro.of(
                    BuildTargetWithOutputs.of(node.getBuildTarget(), OutputLabel.defaultLabel()),
                    ImmutableSet.of(CxxDescriptionEnhancer.SHARED_FLAVOR))),
            pathResolver);
    assertThat(
        expanded,
        Matchers.equalTo(pathResolver.getAbsolutePath(lib.getSourcePathToOutput()).toString()));
  }
}

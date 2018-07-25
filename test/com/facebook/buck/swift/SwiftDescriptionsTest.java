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

package com.facebook.buck.swift;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.CxxLibraryDescriptionArg;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Test;

public class SwiftDescriptionsTest {

  @Test
  public void testPopulateSwiftLibraryDescriptionArg() throws Exception {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");

    SwiftLibraryDescriptionArg.Builder outputBuilder =
        SwiftLibraryDescriptionArg.builder().setName("bar").setVersion("3");

    CxxLibraryDescriptionArg.Builder args = CxxLibraryDescriptionArg.builder().setName("bar");

    PathSourcePath swiftSrc = FakeSourcePath.of("foo/bar.swift");

    args.setSrcs(
        ImmutableSortedSet.of(
            SourceWithFlags.of(FakeSourcePath.of("foo/foo.cpp")), SourceWithFlags.of(swiftSrc)));

    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        pathResolver, outputBuilder, args.build(), buildTarget);
    SwiftLibraryDescriptionArg output = outputBuilder.build();
    assertThat(output.getModuleName().get(), equalTo("bar"));
    assertThat(output.getSrcs(), equalTo(ImmutableSortedSet.<SourcePath>of(swiftSrc)));

    args.setModuleName("baz");

    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        pathResolver, outputBuilder, args.build(), buildTarget);
    output = outputBuilder.build();
    assertThat(output.getModuleName().get(), equalTo("baz"));
    assertThat(output.getVersion().get(), equalTo("3"));
  }
}

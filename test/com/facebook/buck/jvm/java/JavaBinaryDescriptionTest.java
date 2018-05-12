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

package com.facebook.buck.jvm.java;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class JavaBinaryDescriptionTest {

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() throws Exception {
    BuildRuleResolver resolver = new TestBuildRuleResolver();

    FakeJavaLibrary transitiveLibrary =
        resolver.addToIndex(
            new FakeJavaLibrary(BuildTargetFactory.newInstance("//:transitive_lib")));
    FakeJavaLibrary firstOrderLibrary =
        resolver.addToIndex(
            new FakeJavaLibrary(
                BuildTargetFactory.newInstance("//:first_order_lib"),
                ImmutableSortedSet.of(transitiveLibrary)));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaBinary =
        new JavaBinaryRuleBuilder(target)
            .setDeps(ImmutableSortedSet.of(firstOrderLibrary.getBuildTarget()))
            .build(resolver);

    assertThat(
        javaBinary.getBuildDeps(),
        Matchers.containsInAnyOrder(firstOrderLibrary, transitiveLibrary));
  }

  @Test
  public void defaultCxxPlatform() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withFlavor(InternalFlavor.of("newplatform"));
    SourcePath lib = FakeSourcePath.of("lib");

    PrebuiltCxxLibraryBuilder cxxLibBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx_lib"))
            .setPlatformSharedLib(
                PatternMatchedCollection.<SourcePath>builder()
                    .add(Pattern.compile(cxxPlatform.getFlavor().toString(), Pattern.LITERAL), lib)
                    .build());
    JavaBinaryRuleBuilder javaBinBuilder =
        new JavaBinaryRuleBuilder(
                BuildTargetFactory.newInstance("//:bin"),
                CxxPlatformUtils.DEFAULT_PLATFORM,
                FlavorDomain.of("C/C++ Platform", cxxPlatform))
            .setDefaultCxxPlatform(cxxPlatform.getFlavor())
            .setDeps(ImmutableSortedSet.of(cxxLibBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(cxxLibBuilder.build(), javaBinBuilder.build());
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);

    JarFattener javaBinary = (JarFattener) resolver.requireRule(javaBinBuilder.getTarget());

    assertThat(javaBinary.getNativeLibraries().values(), Matchers.contains(lib));
  }
}

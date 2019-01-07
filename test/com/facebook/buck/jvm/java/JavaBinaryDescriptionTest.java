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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class JavaBinaryDescriptionTest {

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    FakeJavaLibrary transitiveLibrary =
        graphBuilder.addToIndex(
            new FakeJavaLibrary(BuildTargetFactory.newInstance("//:transitive_lib")));
    FakeJavaLibrary firstOrderLibrary =
        graphBuilder.addToIndex(
            new FakeJavaLibrary(
                BuildTargetFactory.newInstance("//:first_order_lib"),
                ImmutableSortedSet.of(transitiveLibrary)));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaBinary =
        new JavaBinaryRuleBuilder(target)
            .setDeps(ImmutableSortedSet.of(firstOrderLibrary.getBuildTarget()))
            .build(graphBuilder);

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
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    JarFattener javaBinary = (JarFattener) graphBuilder.requireRule(javaBinBuilder.getTarget());

    assertThat(javaBinary.getNativeLibraries().values(), Matchers.contains(lib));
  }

  @Test
  public void fatJarClasspath() {
    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;
    SourcePath lib = FakeSourcePath.of("lib");

    PrebuiltCxxLibraryBuilder cxxLibBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:cxx_lib"))
            .setSharedLib(lib);
    JavaLibraryBuilder javaLibBuilder =
        new JavaLibraryBuilder(
                BuildTargetFactory.newInstance("//:lib"), new FakeProjectFilesystem(), null)
            .addDep(cxxLibBuilder.getTarget())
            .addSrc(Paths.get("test/source.java"));
    JavaBinaryRuleBuilder javaBinBuilder =
        new JavaBinaryRuleBuilder(
                BuildTargetFactory.newInstance("//:bin"),
                CxxPlatformUtils.DEFAULT_PLATFORM,
                FlavorDomain.of("C/C++ Platform", cxxPlatform))
            .setDefaultCxxPlatform(cxxPlatform.getFlavor())
            .setDeps(ImmutableSortedSet.of(javaLibBuilder.getTarget()));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            cxxLibBuilder.build(), javaLibBuilder.build(), javaBinBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    JarFattener jarFattener = (JarFattener) graphBuilder.requireRule(javaBinBuilder.getTarget());
    ImmutableSet<SourcePath> transitiveClasspaths = jarFattener.getTransitiveClasspaths();
    assertThat(transitiveClasspaths, hasSize(1));
    SourcePath entry = Iterables.getFirst(transitiveClasspaths, null);
    assertThat(entry, is(instanceOf(BuildTargetSourcePath.class)));
    assertThat(
        ((BuildTargetSourcePath) entry).getTarget(), is(equalTo(javaLibBuilder.getTarget())));
  }
}

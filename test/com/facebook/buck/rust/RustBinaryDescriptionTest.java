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

package com.facebook.buck.rust;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.PrebuiltCxxLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;


public class RustBinaryDescriptionTest {
  @Before
  public void ensureRustIsAvailable() throws IOException, InterruptedException {
    RustAssumptions.assumeRustCompilerAvailable();
  }

  // Return all output paths of transitive dependencies, subject to predicate filter.
  private static ImmutableSet<String> getTransitiveOutputPaths(
      RustBinary binary,
      Linker.LinkableDepType linktype,
      Predicate<BuildRule> filter) throws NoSuchBuildTargetException {
    Stream<Path> nativepaths = RustLinkables.getNativePaths(
        binary.getDeps(),
        linktype,
        CxxPlatformUtils.DEFAULT_PLATFORM);
    ImmutableSortedSet<BuildRule> deps = binary.getDeps();

    return Stream.concat(
        nativepaths,
        deps.stream()
            .filter(filter)
            .map(BuildRule::getPathToOutput)
            .filter(Objects::nonNull)
    )
    .map(Path::getFileName)
    .map(Path::toString)
    .collect(MoreCollectors.toImmutableSet());
  }

  private static ImmutableSet<String> getLinkablePaths(
      RustBinary binary,
      Linker.LinkableDepType linktype) throws NoSuchBuildTargetException {
    return getTransitiveOutputPaths(
        binary,
        linktype,
        rule -> rule instanceof RustLinkable);
  }

  @Test
  public void binaryDependsOnLibraryRlib() throws NoSuchBuildTargetException {
    RustLibraryBuilder rustBuilder =
        new RustLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(
                ImmutableSortedSet.of(new FakeSourcePath("simple-dep.rs")));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(rustBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                rustBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    rustBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.SHARED),
        ImmutableSet.of("libsimple_dep.rlib"));
  }

  @Test
  public void depsToLibStaticTransitiveCxx() throws Exception {
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("transitive-dep.c"))));

    RustLibraryBuilder rustBuilder =
        new RustLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("simple-dep.rs")))
            .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(rustBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")))
        .setLinkStyle(Optional.of(Linker.LinkableDepType.STATIC));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxBuilder.build(),
                rustBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBuilder.build(resolver);
    rustBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.STATIC),
        ImmutableSet.of("libsimple_dep.rlib", "libtransitive_dep.a"));
  }

  @Test
  public void depsToLibSharedTransitiveCxx() throws Exception {
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("transitive-dep.c"))));

    RustLibraryBuilder rustBuilder =
        new RustLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("simple-dep.rs")))
            .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(rustBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")))
        .setLinkStyle(Optional.of(Linker.LinkableDepType.SHARED));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxBuilder.build(),
                rustBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBuilder.build(resolver);
    rustBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.SHARED),
        ImmutableSet.of("libsimple_dep.rlib", "libtransitive_dep.so"));
  }

  @Test
  public void depsToLibSharedTransitivePrebuiltCxx() throws Exception {
    PrebuiltCxxLibraryBuilder cxxBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"));

    RustLibraryBuilder rustBuilder =
        new RustLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("simple-dep.rs")))
            .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(rustBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxBuilder.build(),
                rustBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBuilder.build(resolver);
    rustBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.SHARED),
        ImmutableSet.of("libsimple_dep.rlib", "libtransitive_dep.so"));
  }

  @Test
  public void depsToSharedCxx() throws Exception {
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("simple-dep.c"))));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.SHARED),
        ImmutableSet.of("libsimple_dep.so"));
  }

  @Test
  public void depsToStaticCxx() throws Exception {
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("simple-dep.c"))));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.STATIC),
        ImmutableSet.of("libsimple_dep.a"));
  }

  @Test
  public void depsToPrebuiltCxx() throws Exception {
    PrebuiltCxxLibraryBuilder cxxBuilder =
        new PrebuiltCxxLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.SHARED),
        ImmutableSet.of("libsimple_dep.so"));
  }

  @Test
  public void transitiveSharedDepsToCxx() throws Exception {
    CxxLibraryBuilder cxxTransitiveBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("transitive-dep.c"))));

    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("simple-dep.c"))))
            .setDeps(ImmutableSortedSet.of(cxxTransitiveBuilder.getTarget()));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxTransitiveBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxTransitiveBuilder.build(resolver);
    cxxBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.SHARED),
        ImmutableSet.of("libsimple_dep.so"));
  }

  @Test
  public void transitiveStaticDepsToCxx() throws Exception {
    CxxLibraryBuilder cxxTransitiveBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("transitive-dep.c"))));

    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("simple-dep.c"))))
            .setDeps(ImmutableSortedSet.of(cxxTransitiveBuilder.getTarget()));

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
        .setSrcs(ImmutableSortedSet.of(new FakeSourcePath("foo.rs")));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                cxxTransitiveBuilder.build(),
                cxxBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    cxxTransitiveBuilder.build(resolver);
    cxxBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getLinkablePaths(binary, Linker.LinkableDepType.STATIC),
        ImmutableSet.of("libsimple_dep.a", "libtransitive_dep.a"));
  }

  @Test
  public void depsWithGensrcAndCxx() throws Exception {
    CxxLibraryBuilder cxxBuilder =
        new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:simple_dep"))
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("simple-dep.c"))));
    GenruleBuilder gensrc =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:gensrc"))
            .setOut("gen-thing.rs")
            .setCmd("touch $OUT");

    RustBinaryBuilder binaryBuilder =
        new RustBinaryBuilder(
            BuildTargetFactory.newInstance("//:bin"),
            new FakeRustConfig());
    binaryBuilder
        .setDeps(ImmutableSortedSet.of(cxxBuilder.getTarget()))
        .setSrcs(
            ImmutableSortedSet.of(
                new FakeSourcePath("foo.rs"), new BuildTargetSourcePath(gensrc.getTarget())));

    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(
                gensrc.build(),
                cxxBuilder.build(),
                binaryBuilder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    gensrc.build(resolver);
    cxxBuilder.build(resolver);
    RustBinary binary = (RustBinary) binaryBuilder.build(resolver);
    assertEquals(
        getTransitiveOutputPaths(binary, Linker.LinkableDepType.STATIC, x -> true),
        ImmutableSet.of("gen-thing.rs", "libsimple_dep.a"));
  }
}

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
package com.facebook.buck.cxx;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.macros.CcFlagsMacro;
import com.facebook.buck.rules.macros.CcMacro;
import com.facebook.buck.rules.macros.CppFlagsMacro;
import com.facebook.buck.rules.macros.CxxFlagsMacro;
import com.facebook.buck.rules.macros.CxxMacro;
import com.facebook.buck.rules.macros.CxxppFlagsMacro;
import com.facebook.buck.rules.macros.LdMacro;
import com.facebook.buck.rules.macros.LdflagsSharedFilterMacro;
import com.facebook.buck.rules.macros.LdflagsSharedMacro;
import com.facebook.buck.rules.macros.LdflagsStaticFilterMacro;
import com.facebook.buck.rules.macros.LdflagsStaticPicFilterMacro;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroContainer;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.testutil.OptionalMatchers;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.NaiveVersionSelector;
import com.facebook.buck.versions.VersionPropagatorBuilder;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.facebook.buck.versions.VersionedTargetGraphBuilder;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxGenruleDescriptionTest {

  private static final ForkJoinPool POOL = new ForkJoinPool(1);

  @Test
  public void toolPlatformParseTimeDeps() {
    for (Macro macro : ImmutableList.of(LdMacro.of(), CcMacro.of(), CxxMacro.of())) {
      CxxGenruleBuilder builder =
          new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:rule#default"))
              .setCmd(StringWithMacrosUtils.format("%s", macro))
              .setOut("foo");
      assertThat(
          ImmutableSet.copyOf(builder.findImplicitDeps()),
          Matchers.equalTo(
              ImmutableSet.copyOf(
                  CxxPlatforms.getParseTimeDeps(CxxPlatformUtils.DEFAULT_PLATFORM))));
    }
  }

  @Test
  public void ldFlagsFilter() {
    for (BiFunction<Optional<Pattern>, ImmutableList<BuildTarget>, Macro> macro :
        ImmutableList.<BiFunction<Optional<Pattern>, ImmutableList<BuildTarget>, Macro>>of(
            LdflagsSharedFilterMacro::of,
            LdflagsStaticFilterMacro::of,
            LdflagsStaticPicFilterMacro::of)) {
      CxxLibraryBuilder bBuilder =
          new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:b"))
              .setExportedLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("-b")));
      CxxLibraryBuilder aBuilder =
          new CxxLibraryBuilder(BuildTargetFactory.newInstance("//:a"))
              .setExportedDeps(ImmutableSortedSet.of(bBuilder.getTarget()))
              .setExportedLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("-a")));
      CxxGenruleBuilder builder =
          new CxxGenruleBuilder(
                  BuildTargetFactory.newInstance(
                      "//:rule#" + CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor()))
              .setOut("out")
              .setCmd(
                  StringWithMacrosUtils.format(
                      "%s",
                      macro.apply(
                          Optional.of(Pattern.compile("//:a")),
                          ImmutableList.of(aBuilder.getTarget()))));
      TargetGraph targetGraph =
          TargetGraphFactory.newInstance(bBuilder.build(), aBuilder.build(), builder.build());
      ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
      SourcePathResolver pathResolver =
          DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
      bBuilder.build(graphBuilder);
      aBuilder.build(graphBuilder);
      Genrule genrule = (Genrule) builder.build(graphBuilder);
      assertThat(
          Joiner.on(' ')
              .join(Arg.stringify(ImmutableList.of(genrule.getCmd().get()), pathResolver)),
          Matchers.containsString("-a"));
      assertThat(
          Joiner.on(' ')
              .join(Arg.stringify(ImmutableList.of(genrule.getCmd().get()), pathResolver)),
          Matchers.not(Matchers.containsString("-b")));
    }
  }

  @Test
  public void cppflagsNoArgs() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM.withCppflags("-cppflag").withCxxppflags("-cxxppflag");
    CxxGenruleBuilder builder =
        new CxxGenruleBuilder(
                BuildTargetFactory.newInstance("//:rule#" + cxxPlatform.getFlavor()),
                new FlavorDomain<>(
                    "C/C++ Platform", ImmutableMap.of(cxxPlatform.getFlavor(), cxxPlatform)))
            .setOut("out")
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s %s",
                    CppFlagsMacro.of(Optional.empty(), ImmutableList.of()),
                    CxxppFlagsMacro.of(Optional.empty(), ImmutableList.of())));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule genrule = (Genrule) builder.build(graphBuilder);
    assertThat(
        Joiner.on(' ').join(Arg.stringify(ImmutableList.of(genrule.getCmd().get()), pathResolver)),
        Matchers.containsString("-cppflag -cxxppflag"));
  }

  @Test
  public void cflagsNoArgs() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withAsflags("-asflag")
            .withCflags("-cflag")
            .withCxxflags("-cxxflag");
    CxxGenruleBuilder builder =
        new CxxGenruleBuilder(
                BuildTargetFactory.newInstance("//:rule#" + cxxPlatform.getFlavor()),
                new FlavorDomain<>(
                    "C/C++ Platform", ImmutableMap.of(cxxPlatform.getFlavor(), cxxPlatform)))
            .setOut("out")
            .setCmd(StringWithMacrosUtils.format("%s %s", CcFlagsMacro.of(), CxxFlagsMacro.of()));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Genrule genrule = (Genrule) builder.build(graphBuilder);
    for (String expected : ImmutableList.of("-asflag", "-cflag", "-cxxflag")) {
      assertThat(
          Joiner.on(' ')
              .join(Arg.stringify(ImmutableList.of(genrule.getCmd().get()), pathResolver)),
          Matchers.containsString(expected));
    }
  }

  @Test
  public void versionedTargetReferenceIsTranslatedInVersionedGraph() throws Exception {
    VersionPropagatorBuilder dep = new VersionPropagatorBuilder("//:dep");
    VersionedAliasBuilder versionedDep =
        new VersionedAliasBuilder("//:versioned").setVersions("1.0", "//:dep");
    CxxGenruleBuilder genruleBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s",
                    LdflagsSharedMacro.of(
                        Optional.empty(), ImmutableList.of(versionedDep.getTarget()))))
            .setOut("foo");
    TargetGraph graph =
        TargetGraphFactory.newInstance(dep.build(), versionedDep.build(), genruleBuilder.build());
    TargetGraphAndBuildTargets transformed =
        VersionedTargetGraphBuilder.transform(
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(graph, ImmutableSet.of(genruleBuilder.getTarget())),
            POOL,
            new DefaultTypeCoercerFactory());
    CxxGenruleDescriptionArg arg =
        extractArg(
            transformed.getTargetGraph().get(genruleBuilder.getTarget()),
            CxxGenruleDescriptionArg.class);
    assertThat(
        arg.getCmd(),
        OptionalMatchers.present(
            Matchers.equalTo(
                StringWithMacrosUtils.format(
                    "%s",
                    LdflagsSharedMacro.of(Optional.empty(), ImmutableList.of(dep.getTarget()))))));
  }

  @Test
  public void versionPropagatorTargetReferenceIsTranslatedInVersionedGraph() throws Exception {
    VersionPropagatorBuilder transitiveDep = new VersionPropagatorBuilder("//:transitive_dep");
    VersionedAliasBuilder versionedDep =
        new VersionedAliasBuilder("//:versioned").setVersions("1.0", "//:transitive_dep");
    VersionPropagatorBuilder dep = new VersionPropagatorBuilder("//:dep").setDeps("//:versioned");
    CxxGenruleBuilder genruleBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s",
                    LdflagsSharedMacro.of(Optional.empty(), ImmutableList.of(dep.getTarget()))))
            .setOut("foo");
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            transitiveDep.build(), versionedDep.build(), dep.build(), genruleBuilder.build());
    TargetGraphAndBuildTargets transformed =
        VersionedTargetGraphBuilder.transform(
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(graph, ImmutableSet.of(genruleBuilder.getTarget())),
            POOL,
            new DefaultTypeCoercerFactory());
    CxxGenruleDescriptionArg arg =
        extractArg(
            transformed.getTargetGraph().get(genruleBuilder.getTarget()),
            CxxGenruleDescriptionArg.class);
    assertThat(
        RichStream.from(arg.getCmd().orElseThrow(AssertionError::new).getMacros())
            .map(MacroContainer::getMacro)
            .filter(LdflagsSharedMacro.class)
            .flatMap(m -> m.getTargets().stream())
            .map(BuildTarget::getFullyQualifiedName)
            .collect(Collectors.toList()),
        Matchers.contains(Matchers.matchesPattern(Pattern.quote("//:dep#v") + "[a-zA-Z0-9]*")));
  }

  @Test
  public void cxxGenruleInLocationMacro() {
    CxxGenruleBuilder depBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:dep")).setOut("out");
    CxxGenruleBuilder builder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setCmd(StringWithMacrosUtils.format("%s", LocationMacro.of(depBuilder.getTarget())))
            .setOut("out");
    TargetGraph targetGraph = TargetGraphFactory.newInstance(depBuilder.build(), builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    CxxGenrule dep = (CxxGenrule) graphBuilder.requireRule(depBuilder.getTarget());
    CxxGenrule rule = (CxxGenrule) graphBuilder.requireRule(builder.getTarget());
    Genrule genrule =
        (Genrule)
            ruleFinder
                .getRule(rule.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder))
                .orElseThrow(AssertionError::new);
    assertThat(
        Arg.stringify(Optionals.toStream(genrule.getCmd()).toOnceIterable(), pathResolver),
        Matchers.contains(
            pathResolver
                .getAbsolutePath(dep.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder))
                .toString()));
  }

  @Test
  public void isCacheable() {
    CxxGenruleBuilder builder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setCmd(StringWithMacrosUtils.format("touch $OUT"))
            .setCacheable(false);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    CxxGenrule rule = (CxxGenrule) graphBuilder.requireRule(builder.getTarget());
    Genrule genrule =
        (Genrule)
            ruleFinder
                .getRule(rule.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder))
                .orElseThrow(AssertionError::new);
    assertFalse(genrule.isCacheable());
  }

  private static <U> U extractArg(TargetNode<?> node, Class<U> clazz) {
    return TargetNodes.castArg(node, clazz)
        .orElseThrow(
            () ->
                new AssertionError(
                    String.format(
                        "%s: expected constructor arg to be of type %s (was %s)",
                        node, clazz, node.getConstructorArg().getClass())))
        .getConstructorArg();
  }
}

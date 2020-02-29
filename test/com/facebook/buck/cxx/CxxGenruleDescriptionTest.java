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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.impl.StaticUnresolvedCxxPlatform;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
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
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.OptionalMatchers;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.versions.AsyncVersionedTargetGraphBuilder;
import com.facebook.buck.versions.NaiveVersionSelector;
import com.facebook.buck.versions.VersionPropagatorBuilder;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class CxxGenruleDescriptionTest {

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

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
                  CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM.getParseTimeDeps(
                      UnconfiguredTargetConfiguration.INSTANCE))));
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
                      "//:rule#" + CxxPlatformUtils.DEFAULT_PLATFORM_FLAVOR))
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
      bBuilder.build(graphBuilder);
      aBuilder.build(graphBuilder);
      Genrule genrule = (Genrule) builder.build(graphBuilder);
      assertThat(
          Joiner.on(' ')
              .join(
                  Arg.stringify(
                      ImmutableList.of(genrule.getBuildable().getCmd().get()),
                      graphBuilder.getSourcePathResolver())),
          Matchers.containsString("-a"));
      assertThat(
          Joiner.on(' ')
              .join(
                  Arg.stringify(
                      ImmutableList.of(genrule.getBuildable().getCmd().get()),
                      graphBuilder.getSourcePathResolver())),
          Matchers.not(Matchers.containsString("-b")));
    }
  }

  @Test
  public void cppflagsNoArgs() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withCppflags(ImmutableList.of(StringArg.of("-cppflag")))
            .withCxxppflags(ImmutableList.of(StringArg.of("-cxxppflag")));
    CxxGenruleBuilder builder =
        new CxxGenruleBuilder(
                BuildTargetFactory.newInstance("//:rule#" + cxxPlatform.getFlavor()),
                new FlavorDomain<>(
                    "C/C++ Platform",
                    ImmutableMap.of(
                        cxxPlatform.getFlavor(), new StaticUnresolvedCxxPlatform(cxxPlatform))))
            .setOut("out")
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s %s",
                    CppFlagsMacro.of(Optional.empty(), ImmutableList.of()),
                    CxxppFlagsMacro.of(Optional.empty(), ImmutableList.of())));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    Genrule genrule = (Genrule) builder.build(graphBuilder);
    assertThat(
        Joiner.on(' ')
            .join(
                Arg.stringify(
                    ImmutableList.of(genrule.getBuildable().getCmd().get()),
                    graphBuilder.getSourcePathResolver())),
        Matchers.containsString("-cppflag -cxxppflag"));
  }

  @Test
  public void cflagsNoArgs() {
    CxxPlatform cxxPlatform =
        CxxPlatformUtils.DEFAULT_PLATFORM
            .withAsflags(ImmutableList.of(StringArg.of("-asflag")))
            .withCflags(ImmutableList.of(StringArg.of("-cflag")))
            .withCxxflags(ImmutableList.of(StringArg.of("-cxxflag")));
    CxxGenruleBuilder builder =
        new CxxGenruleBuilder(
                BuildTargetFactory.newInstance("//:rule#" + cxxPlatform.getFlavor()),
                new FlavorDomain<>(
                    "C/C++ Platform",
                    ImmutableMap.of(
                        cxxPlatform.getFlavor(), new StaticUnresolvedCxxPlatform(cxxPlatform))))
            .setOut("out")
            .setCmd(StringWithMacrosUtils.format("%s %s", CcFlagsMacro.of(), CxxFlagsMacro.of()));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    Genrule genrule = (Genrule) builder.build(graphBuilder);
    for (String expected : ImmutableList.of("-asflag", "-cflag", "-cxxflag")) {
      assertThat(
          Joiner.on(' ')
              .join(
                  Arg.stringify(
                      ImmutableList.of(genrule.getBuildable().getCmd().get()),
                      graphBuilder.getSourcePathResolver())),
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
    TargetGraphCreationResult transformed =
        AsyncVersionedTargetGraphBuilder.transform(
            new NaiveVersionSelector(),
            TargetGraphCreationResult.of(graph, ImmutableSet.of(genruleBuilder.getTarget())),
            executor.get(),
            new DefaultTypeCoercerFactory(),
            new ParsingUnconfiguredBuildTargetViewFactory(),
            20,
            new TestCellBuilder().build());
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
    TargetGraphCreationResult transformed =
        AsyncVersionedTargetGraphBuilder.transform(
            new NaiveVersionSelector(),
            TargetGraphCreationResult.of(graph, ImmutableSet.of(genruleBuilder.getTarget())),
            executor.get(),
            new DefaultTypeCoercerFactory(),
            new ParsingUnconfiguredBuildTargetViewFactory(),
            20,
            new TestCellBuilder().build());
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
    CxxGenrule dep = (CxxGenrule) graphBuilder.requireRule(depBuilder.getTarget());
    CxxGenrule rule = (CxxGenrule) graphBuilder.requireRule(builder.getTarget());
    Genrule genrule =
        (Genrule)
            graphBuilder
                .getRule(rule.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder))
                .orElseThrow(AssertionError::new);
    assertThat(
        Arg.stringify(
            RichStream.from(genrule.getBuildable().getCmd()).toOnceIterable(),
            graphBuilder.getSourcePathResolver()),
        Matchers.contains(
            graphBuilder
                .getSourcePathResolver()
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
    CxxGenrule rule = (CxxGenrule) graphBuilder.requireRule(builder.getTarget());
    Genrule genrule =
        (Genrule)
            graphBuilder
                .getRule(rule.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder))
                .orElseThrow(AssertionError::new);
    assertFalse(genrule.isCacheable());
  }

  @Test
  public void testCxxGenrulesAreExecutableWithConfig() {
    CxxGenruleBuilder builder =
        new CxxGenruleBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                FakeBuckConfig.builder()
                    .setSections(
                        RawConfig.builder()
                            .put(
                                RemoteExecutionConfig.SECTION,
                                RemoteExecutionConfig
                                    .getUseRemoteExecutionForGenruleIfRequestedField("cxx_genrule"),
                                "true")
                            .build())
                    .build())
            .setOut("out")
            .setCmd(StringWithMacrosUtils.format("touch $OUT"))
            .setRemote(true);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(builder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    CxxGenrule rule = (CxxGenrule) graphBuilder.requireRule(builder.getTarget());
    Genrule genrule =
        (Genrule)
            graphBuilder
                .getRule(rule.getGenrule(CxxPlatformUtils.DEFAULT_PLATFORM, graphBuilder))
                .orElseThrow(AssertionError::new);
    assertTrue(genrule.getBuildable().shouldExecuteRemotely());
  }

  private static <U extends ConstructorArg> U extractArg(TargetNode<?> node, Class<U> clazz) {
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

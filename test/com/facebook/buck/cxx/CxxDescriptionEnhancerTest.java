/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class CxxDescriptionEnhancerTest {

  private static FakeBuildRule createFakeBuildRule(
      String target,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeps(ImmutableSortedSet.copyOf(deps))
            .build());
  }

  @Test
  public void createLexYaccBuildRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Setup our C++ buck config with the paths to the lex/yacc binaries.
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path lexPath = Paths.get("lex");
    filesystem.touch(lexPath);
    Path yaccPath = Paths.get("yacc");
    filesystem.touch(yaccPath);
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.of(
                "lex", lexPath.toString(),
                "yacc", yaccPath.toString())),
        filesystem);
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    // Setup a genrule that generates our lex source.
    String lexSourceName = "test.ll";
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:genrule_lex");
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut(lexSourceName)
        .build(resolver);
    SourcePath lexSource = new BuildRuleSourcePath(genrule);

    // Use a regular path for our yacc source.
    String yaccSourceName = "test.yy";
    SourcePath yaccSource = new TestSourcePath(yaccSourceName);

    // Build the rules.
    CxxHeaderSourceSpec actual = CxxDescriptionEnhancer.createLexYaccBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        ImmutableList.<String>of(),
        ImmutableMap.of(lexSourceName, lexSource),
        ImmutableList.<String>of(),
        ImmutableMap.of(yaccSourceName, yaccSource));

    // Grab the generated lex rule and verify it has the genrule as a dep.
    Lex lex = (Lex) resolver.getRule(
        CxxDescriptionEnhancer.createLexBuildTarget(target, lexSourceName));
    assertNotNull(lex);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        lex.getDeps());

    // Grab the generated yacc rule and verify it has no deps.
    Yacc yacc = (Yacc) resolver.getRule(
        CxxDescriptionEnhancer.createYaccBuildTarget(target, yaccSourceName));
    assertNotNull(yacc);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(),
        yacc.getDeps());

    // Check the header/source spec is correct.
    Path lexOutputSource = CxxDescriptionEnhancer.getLexSourceOutputPath(target, lexSourceName);
    Path lexOutputHeader = CxxDescriptionEnhancer.getLexHeaderOutputPath(target, lexSourceName);
    Path yaccOutputPrefix = CxxDescriptionEnhancer.getYaccOutputPrefix(target, yaccSourceName);
    Path yaccOutputSource = Yacc.getSourceOutputPath(yaccOutputPrefix);
    Path yaccOutputHeader = Yacc.getHeaderOutputPath(yaccOutputPrefix);
    CxxHeaderSourceSpec expected = new CxxHeaderSourceSpec(
        ImmutableMap.<Path, SourcePath>of(
            target.getBasePath().resolve(lexSourceName + ".h"),
            new BuildRuleSourcePath(lex, lexOutputHeader),
            target.getBasePath().resolve(yaccSourceName + ".h"),
            new BuildRuleSourcePath(yacc, yaccOutputHeader)),
        ImmutableList.of(
            new CxxSource(
                lexSourceName + ".cc",
                new BuildRuleSourcePath(lex, lexOutputSource)),
            new CxxSource(
                yaccSourceName + ".cc",
                new BuildRuleSourcePath(yacc, yaccOutputSource))));
    assertEquals(expected, actual);
  }


  @Test
  public void linkWhole() {
    FakeBuckConfig buckConfig = new FakeBuckConfig();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    String sourceName = "test.cc";
    CxxSource source = new CxxSource(
        sourceName,
        new TestSourcePath(sourceName));

    // First, create a cxx library without using link whole.
    CxxLibrary normal = CxxDescriptionEnhancer.createCxxLibraryBuildRules(
        params,
        new BuildRuleResolver(),
        cxxBuckConfig,
        /* preprocessorFlags */ ImmutableList.<String>of(),
        /* propagatedPpFlags */ ImmutableList.<String>of(),
        /* headers */ ImmutableMap.<Path, SourcePath>of(),
        /* compilerFlags */ ImmutableList.<String>of(),
        /* sources */ ImmutableList.of(source),
        /* linkWhole */ false);

    // Verify that the linker args contains the link whole flags.
    assertFalse(normal.getNativeLinkableInput(
        NativeLinkable.Type.STATIC).getArgs().contains("--whole-archive"));
    assertFalse(normal.getNativeLinkableInput(
        NativeLinkable.Type.STATIC).getArgs().contains("--no-whole-archive"));

    // Create a cxx library using link whole.
    CxxLibrary linkWhole = CxxDescriptionEnhancer.createCxxLibraryBuildRules(
        params,
        new BuildRuleResolver(),
        cxxBuckConfig,
        /* preprocessorFlags */ ImmutableList.<String>of(),
        /* propagatedPpFlags */ ImmutableList.<String>of(),
        /* headers */ ImmutableMap.<Path, SourcePath>of(),
        /* compilerFlags */ ImmutableList.<String>of(),
        /* sources */ ImmutableList.of(source),
        /* linkWhole */ true);

    // Verify that the linker args contains the link whole flags.
    assertTrue(linkWhole.getNativeLinkableInput(
        NativeLinkable.Type.STATIC).getArgs().contains("--whole-archive"));
    assertTrue(linkWhole.getNativeLinkableInput(
        NativeLinkable.Type.STATIC).getArgs().contains("--no-whole-archive"));
  }

  @Test
  @SuppressWarnings("PMD.UseAssertTrueInsteadOfAssertEquals")
  public void createCxxLibraryBuildRules() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Setup a normal C++ source
    String sourceName = "test/bar.cpp";

    // Setup a genrule the generates a header we'll list.
    String genHeaderName = "test/foo.h";
    BuildTarget genHeaderTarget = BuildTargetFactory.newInstance("//:genHeader");
    Genrule genHeader = (Genrule) GenruleBuilder
        .newGenruleBuilder(genHeaderTarget)
        .setOut(genHeaderName)
        .build(resolver);

    // Setup a genrule the generates a source we'll list.
    String genSourceName = "test/foo.cpp";
    BuildTarget genSourceTarget = BuildTargetFactory.newInstance("//:genSource");
    Genrule genSource = (Genrule) GenruleBuilder
        .newGenruleBuilder(genSourceTarget)
        .setOut(genSourceName)
        .build(resolver);

    // Setup a C/C++ library that we'll depend on form the C/C++ binary description.
    final BuildRule header = createFakeBuildRule("//:header");
    final BuildRule headerSymlinkTree = createFakeBuildRule("//:symlink");
    final Path headerSymlinkTreeRoot = Paths.get("symlink/tree/root");
    final BuildRule staticLibraryDep = createFakeBuildRule("//:static");
    final Path staticLibraryOutput = Paths.get("output/path/lib.a");
    final BuildRule sharedLibraryDep = createFakeBuildRule("//:shared");
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "soname";
    BuildTarget depTarget = BuildTargetFactory.newInstance("//:dep");
    BuildRuleParams depParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(depTarget);
    CxxLibrary dep = new CxxLibrary(depParams) {

      @Override
      public CxxPreprocessorInput getCxxPreprocessorInput() {
        return new CxxPreprocessorInput(
            ImmutableSet.of(
                header.getBuildTarget(),
                headerSymlinkTree.getBuildTarget()),
            ImmutableList.<String>of(),
            ImmutableList.<String>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableList.of(headerSymlinkTreeRoot),
            ImmutableList.<Path>of());
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(Type type) {
        return type == Type.STATIC ?
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(new BuildRuleSourcePath(staticLibraryDep)),
                ImmutableList.of(staticLibraryOutput.toString())) :
            new NativeLinkableInput(
                ImmutableList.<SourcePath>of(new BuildRuleSourcePath(sharedLibraryDep)),
                ImmutableList.of(sharedLibraryOutput.toString()));
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents() {
        return new PythonPackageComponents(
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(sharedLibrarySoname), new PathSourcePath(sharedLibraryOutput)));
      }

    };
    resolver.addAllToIndex(
        ImmutableList.of(
            header,
            headerSymlinkTree,
            staticLibraryDep,
            sharedLibraryDep));

    // Setup the build params we'll pass to description when generating the build rules.
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target)
        .setDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();

    // Construct C/C++ library build rules.
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(new FakeBuckConfig());
    CxxLibrary rule = CxxDescriptionEnhancer.createCxxLibraryBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableMap.<Path, SourcePath>of(
            target.getBasePath().resolve(genHeaderName), new BuildRuleSourcePath(genHeader)),
        ImmutableList.<String>of(),
        ImmutableList.of(
            new CxxSource(sourceName, new TestSourcePath(sourceName)),
            new CxxSource(genSourceName, new BuildRuleSourcePath(genSource))),
        /* linkWhole */ false);

    // Verify the C/C++ preprocessor input is setup correctly.
    assertEquals(
        new CxxPreprocessorInput(
            ImmutableSet.of(
                CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
            ImmutableList.<String>of(),
            ImmutableList.<String>of(),
            ImmutableMap.<Path, SourcePath>of(
                Paths.get(genHeaderName), new BuildRuleSourcePath(genHeader)),
            ImmutableList.of(
                CxxDescriptionEnhancer.getHeaderSymlinkTreePath(target)),
            ImmutableList.<Path>of()),
        rule.getCxxPreprocessorInput());

    // Verify that the archive rule has the correct deps: the object files from our sources.
    BuildRule staticRule = resolver.getRule(
        CxxDescriptionEnhancer.createStaticLibraryBuildTarget(target));
    assertNotNull(staticRule);
    assertEquals(
        ImmutableSet.of(
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                "test/bar.cpp",
                /* pic */ false),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                genSourceName,
                /* pic */ false)),
        FluentIterable.from(staticRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule staticCompileRule1 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            "test/bar.cpp",
            /* pic */ false));
    assertNotNull(staticCompileRule1);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(staticCompileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule staticCompileRule2 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            genSourceName,
            /* pic */ false));
    assertNotNull(staticCompileRule2);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(staticCompileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the archive rule has the correct deps: the object files from our sources.
    BuildRule sharedRule = resolver.getRule(
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(target));
    assertNotNull(sharedRule);
    assertEquals(
        ImmutableSet.of(
            sharedLibraryDep.getBuildTarget(),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                "test/bar.cpp",
                /* pic */ true),
            CxxCompilableEnhancer.createCompileBuildTarget(
                target,
                genSourceName,
                /* pic */ true)),
        FluentIterable.from(sharedRule.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our user-provided source has correct deps setup
    // for the various header rules.
    BuildRule sharedCompileRule1 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            "test/bar.cpp",
            /* pic */ true));
    assertNotNull(sharedCompileRule1);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(sharedCompileRule1.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Verify that the compile rule for our genrule-generated source has correct deps setup
    // for the various header rules and the generating genrule.
    BuildRule sharedCompileRule2 = resolver.getRule(
        CxxCompilableEnhancer.createCompileBuildTarget(
            target,
            genSourceName,
            /* pic */ true));
    assertNotNull(sharedCompileRule2);
    assertEquals(
        ImmutableSet.of(
            genHeaderTarget,
            genSourceTarget,
            headerSymlinkTree.getBuildTarget(),
            header.getBuildTarget(),
            CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(target)),
        FluentIterable.from(sharedCompileRule2.getDeps())
            .transform(HasBuildTarget.TO_TARGET)
            .toSet());

    // Check the python interface returning by this C++ library.
    PythonPackageComponents expectedPythonPackageComponents = new PythonPackageComponents(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(CxxDescriptionEnhancer.getSharedLibrarySoname(target)),
            new BuildRuleSourcePath(sharedRule)));
    assertEquals(
        expectedPythonPackageComponents,
        rule.getPythonPackageComponents());
  }

}

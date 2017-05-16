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

package com.facebook.buck.rust;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.Linker;
import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.Test;

public class RustCompileTest {
  @Test(expected = HumanReadableException.class)
  public void noCrateRootInSrcs() {
    RustCompileRule linkable = FakeRustCompileRule.from("//:donotcare", ImmutableSortedSet.of());
    linkable.getCrateRoot();
  }

  @Test
  public void crateRootMainInSrcs() {
    RustCompileRule linkable =
        FakeRustCompileRule.from(
            "//:donotcare", ImmutableSortedSet.of(new FakeSourcePath("main.rs")));
    assertThat(linkable.getCrateRoot().toString(), Matchers.endsWith("main.rs"));
  }

  @Test
  public void crateRootTargetNameInSrcs() {
    RustCompileRule linkable =
        FakeRustCompileRule.from(
            "//:myname", ImmutableSortedSet.of(new FakeSourcePath("myname.rs")));
    assertThat(linkable.getCrateRoot().toString(), Matchers.endsWith("myname.rs"));
  }

  // Test that there's only one valid candidate root source file.
  @Test(expected = HumanReadableException.class)
  public void crateRootMainAndTargetNameInSrcs() {
    RustCompileRule linkable =
        FakeRustCompileRule.from(
            "//:myname",
            ImmutableSortedSet.of(new FakeSourcePath("main.rs"), new FakeSourcePath("myname.rs")));
    linkable.getCrateRoot();
  }

  private static Tool fakeTool() {
    return new Tool() {
      @Override
      public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
        return ImmutableSortedSet.of();
      }

      @Override
      public ImmutableCollection<SourcePath> getInputs() {
        return ImmutableSortedSet.of();
      }

      @Override
      public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
        return ImmutableList.of();
      }

      @Override
      public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
        return ImmutableMap.of();
      }

      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        // Do nothing.
      }
    };
  }

  private static Linker fakeLinker() {
    return new Linker() {
      @Override
      public ImmutableList<FileScrubber> getScrubbers(ImmutableCollection<Path> cellRoots) {
        return null;
      }

      @Override
      public Iterable<Arg> linkWhole(Arg input) {
        return null;
      }

      @Override
      public Iterable<String> soname(String soname) {
        return null;
      }

      @Override
      public Iterable<Arg> fileList(Path fileListPath) {
        return null;
      }

      @Override
      public String origin() {
        return null;
      }

      @Override
      public String libOrigin() {
        return null;
      }

      @Override
      public String searchPathEnvVar() {
        return null;
      }

      @Override
      public String preloadEnvVar() {
        return null;
      }

      @Override
      public Iterable<String> getNoAsNeededSharedLibsFlags() {
        return null;
      }

      @Override
      public Iterable<String> getIgnoreUndefinedSymbolsFlags() {
        return null;
      }

      @Override
      public ImmutableList<Arg> createUndefinedSymbolsLinkerArgs(
          BuildRuleParams baseParams,
          BuildRuleResolver ruleResolver,
          SourcePathRuleFinder ruleFinder,
          BuildTarget target,
          Iterable<? extends SourcePath> symbolFiles) {
        return null;
      }

      @Override
      public Iterable<Arg> getSharedLibFlag() {
        return null;
      }

      @Override
      public Iterable<String> outputArgs(String path) {
        return null;
      }

      @Override
      public boolean hasFilePathSizeLimitations() {
        return false;
      }

      @Override
      public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
        return ImmutableSortedSet.of();
      }

      @Override
      public ImmutableCollection<SourcePath> getInputs() {
        return ImmutableSortedSet.of();
      }

      @Override
      public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
        return ImmutableList.of();
      }

      @Override
      public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
        return ImmutableMap.of();
      }

      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        // Do nothing.
      }
    };
  }

  static class FakeRustCompileRule extends RustCompileRule {
    private FakeRustCompileRule(
        BuildTarget target, ImmutableSortedSet<SourcePath> srcs, SourcePath rootModule) {
      super(
          new FakeBuildRuleParamsBuilder(target).build(),
          String.format("lib%s.rlib", target),
          fakeTool(),
          fakeLinker(),
          Stream.of("--crate-name", target.getShortName(), "--crate-type", "rlib")
              .map(StringArg::of)
              .collect(MoreCollectors.toImmutableList()),
          /* depArgs */ ImmutableList.of(),
          /* linkerFlags */
          ImmutableList.of(),
          srcs,
          rootModule,
          true);
    }

    static FakeRustCompileRule from(String target, ImmutableSortedSet<SourcePath> srcs) {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(target);

      SourcePathRuleFinder ruleFinder =
          new SourcePathRuleFinder(
              new BuildRuleResolver(
                  TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));

      SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

      Optional<SourcePath> root =
          RustCompileUtils.getCrateRoot(
              pathResolver,
              buildTarget.getShortName(),
              ImmutableSet.of("main.rs", "lib.rs"),
              srcs.stream());

      if (!root.isPresent()) {
        throw new HumanReadableException("No crate root source identified");
      }
      return new FakeRustCompileRule(buildTarget, srcs, root.get());
    }
  }
}

/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.features.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PythonPackagedBinaryTest {

  private static final Tool PEX = new CommandTool.Builder().addArg("something").build();

  @Rule public final TemporaryFolder tmpDir = new TemporaryFolder();

  private RuleKey getRuleKeyForModuleLayout(
      DefaultRuleKeyFactory ruleKeyFactory,
      SourcePathRuleFinder ruleFinder,
      String main,
      Path mainSrc,
      String mod1,
      Path src1,
      String mod2,
      Path src2) {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget target = BuildTargetFactory.newInstance("//:bin");

    // The top-level python binary that lists the above libraries as deps.
    PythonBinary binary =
        new PythonPackagedBinary(
            target,
            new FakeProjectFilesystem(),
            ruleFinder,
            ImmutableSortedSet::of,
            PythonTestUtils.PYTHON_PLATFORM,
            PEX,
            ImmutableList.of(),
            new HashedFileTool(
                PathSourcePath.of(projectFilesystem, Paths.get("dummy_path_to_pex_runner"))),
            ".pex",
            new PythonEnvironment(Paths.get("fake_python"), PythonVersion.of("CPython", "2.7")),
            "main",
            PythonPackageComponents.of(
                ImmutableMap.of(
                    Paths.get(main), PathSourcePath.of(projectFilesystem, mainSrc),
                    Paths.get(mod1), PathSourcePath.of(projectFilesystem, src1),
                    Paths.get(mod2), PathSourcePath.of(projectFilesystem, src2)),
                ImmutableMap.of(),
                ImmutableMap.of(),
                ImmutableMultimap.of(),
                Optional.empty()),
            ImmutableSortedSet.of(),
            /* cache */ true,
            /* legacyOutputPath */ false);

    // Calculate and return the rule key.
    return ruleKeyFactory.build(binary);
  }

  @Test
  public void testRuleKeysFromModuleLayouts() throws IOException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    // Create two different sources, which we'll swap in as different modules.
    Path main = tmpDir.newFile().toPath();
    Files.write(main, "main".getBytes(Charsets.UTF_8));
    Path source1 = tmpDir.newFile().toPath();
    Files.write(source1, "hello world".getBytes(Charsets.UTF_8));
    Path source2 = tmpDir.newFile().toPath();
    Files.write(source2, "goodbye world".getBytes(Charsets.UTF_8));

    Path mainRelative = MorePaths.relativize(tmpDir.getRoot().toPath(), main);
    Path source1Relative = MorePaths.relativize(tmpDir.getRoot().toPath(), source1);
    Path source2Relative = MorePaths.relativize(tmpDir.getRoot().toPath(), source2);

    // Setup a rulekey builder factory.
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.of(
                mainRelative.toString(), Strings.repeat("a", 40),
                source1Relative.toString(), Strings.repeat("b", 40),
                source2Relative.toString(), Strings.repeat("c", 40)));

    // Calculate the rule keys for the various ways we can layout the source and modules
    // across different python libraries.
    RuleKey pair1 =
        getRuleKeyForModuleLayout(
            new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder),
            ruleFinder,
            "main.py",
            mainRelative,
            "module/one.py",
            source1Relative,
            "module/two.py",
            source2Relative);
    RuleKey pair2 =
        getRuleKeyForModuleLayout(
            new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder),
            ruleFinder,
            "main.py",
            mainRelative,
            "module/two.py",
            source2Relative,
            "module/one.py",
            source1Relative);
    RuleKey pair3 =
        getRuleKeyForModuleLayout(
            new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder),
            ruleFinder,
            "main.py",
            mainRelative,
            "module/one.py",
            source2Relative,
            "module/two.py",
            source1Relative);
    RuleKey pair4 =
        getRuleKeyForModuleLayout(
            new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder),
            ruleFinder,
            "main.py",
            mainRelative,
            "module/two.py",
            source1Relative,
            "module/one.py",
            source2Relative);

    // Make sure only cases where the actual module layouts are different result
    // in different rules keys.
    assertEquals(pair1, pair2);
    assertEquals(pair3, pair4);
    assertNotEquals(pair1, pair3);
  }
}

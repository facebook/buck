/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.description.DescriptionCache;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class GenAidlTest {

  private ProjectFilesystem stubFilesystem;
  private PathSourcePath pathToAidl;
  private BuildTarget target;
  private DefaultSourcePathResolver pathResolver;
  private String pathToAidlExecutable;
  private String pathToFrameworkAidl;
  private String importPath;
  private AndroidPlatformTarget androidPlatformTarget;

  @Before
  public void setUp() throws IOException {
    stubFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Files.createDirectories(stubFilesystem.getRootPath().resolve("java/com/example/base"));

    pathToAidl = FakeSourcePath.of(stubFilesystem, "java/com/example/base/IWhateverService.aidl");
    importPath = Paths.get("java/com/example/base").toString();

    pathToAidlExecutable = Paths.get("/usr/local/bin/aidl").toString();
    pathToFrameworkAidl =
        Paths.get("/home/root/android/platforms/android-16/framework.aidl").toString();
    androidPlatformTarget =
        AndroidPlatformTarget.of(
            "android",
            Paths.get(""),
            Collections.emptyList(),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(pathToAidlExecutable),
            Paths.get(""),
            Paths.get(""),
            Paths.get(pathToFrameworkAidl),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""));

    target =
        BuildTargetFactory.newInstance(
            stubFilesystem.getRootPath(), "//java/com/example/base:IWhateverService");
    pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
  }

  private GenAidl createGenAidlRule(ImmutableSortedSet<SourcePath> aidlSourceDeps) {
    BuildRuleParams params = TestBuildRuleParams.create();
    return new GenAidl(
        target,
        stubFilesystem,
        new ToolchainProviderBuilder()
            .withToolchain(AndroidPlatformTarget.DEFAULT_NAME, androidPlatformTarget)
            .build(),
        params,
        pathToAidl,
        importPath,
        aidlSourceDeps);
  }

  @Test
  public void testSimpleGenAidlRule() {
    GenAidl genAidlRule = createGenAidlRule(ImmutableSortedSet.of());
    GenAidlDescription description = new GenAidlDescription();
    assertEquals(
        DescriptionCache.getBuildRuleType(GenAidlDescription.class),
        DescriptionCache.getBuildRuleType(description));

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(stubFilesystem.getRootPath());
    List<Step> steps = genAidlRule.getBuildSteps(buildContext, new FakeBuildableContext());

    Path outputDirectory = BuildTargets.getScratchPath(stubFilesystem, target, "__%s.aidl");
    assertEquals(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory))
            .withRecursive(true),
        steps.get(2));
    assertEquals(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), stubFilesystem, outputDirectory)),
        steps.get(3));

    ShellStep aidlStep = (ShellStep) steps.get(4);
    assertEquals(
        "gen_aidl() should use the aidl binary to write .java files.",
        String.format(
            "(cd %s && %s -p%s -I%s -o%s %s)",
            stubFilesystem.getRootPath(),
            pathToAidlExecutable,
            pathToFrameworkAidl,
            stubFilesystem.resolve(importPath),
            stubFilesystem.resolve(outputDirectory),
            pathToAidl.getRelativePath()),
        aidlStep.getDescription(TestExecutionContext.newBuilder().build()));

    assertEquals(7, steps.size());
  }

  @Test
  public void testTransitiveAidlDependenciesAffectTheRuleKey() throws IOException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    StackedFileHashCache hashCache =
        StackedFileHashCache.createDefaultHashCaches(
            stubFilesystem, FileHashCacheMode.LOADING_CACHE);
    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder);
    stubFilesystem.touch(stubFilesystem.getRootPath().resolve(pathToAidl.getRelativePath()));

    GenAidl genAidlRuleNoDeps = createGenAidlRule(ImmutableSortedSet.of());
    RuleKey ruleKey = factory.build(genAidlRuleNoDeps);

    // The rule key is different.
    GenAidl genAidlRuleNoDeps2 = createGenAidlRule(ImmutableSortedSet.of(pathToAidl));
    RuleKey ruleKey2 = factory.build(genAidlRuleNoDeps2);
    assertNotEquals(ruleKey, ruleKey2);

    // And the rule key is stable.
    GenAidl genAidlRuleNoDeps3 = createGenAidlRule(ImmutableSortedSet.of(pathToAidl));
    RuleKey ruleKey3 = factory.build(genAidlRuleNoDeps3);
    assertEquals(ruleKey2, ruleKey3);
  }
}

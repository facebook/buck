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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CalculateClassAbiTest {

  @Test
  public void testIsAbiTargetRecognizesAbiTargets() {
    assertTrue(
        JavaAbis.isClassAbiTarget(BuildTargetFactory.newInstance("//foo/bar:bar#class-abi")));
  }

  @Test
  public void testIsAbiTargetRecognizesNonAbiTargets() {
    assertFalse(JavaAbis.isClassAbiTarget(BuildTargetFactory.newInstance("//foo/bar:bar#not-abi")));
  }

  @Test
  public void testGetLibraryTarget() {
    assertThat(
        JavaAbis.getLibraryTarget(BuildTargetFactory.newInstance("//foo/bar:bar#class-abi")),
        Matchers.equalTo(BuildTargetFactory.newInstance("//foo/bar:bar")));
  }

  @Test
  public void ruleKeysChangeIfGeneratedBinaryJarChanges() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the initial java library.
    Path input = Paths.get("input.java");
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:library");
    JavaLibraryBuilder builder =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(PathSourcePath.of(filesystem, input));
    DefaultJavaLibrary javaLibrary = builder.build(graphBuilder, filesystem);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("stuff", input);
    filesystem.writeContentsToPath(
        "stuff", pathResolver.getRelativePath(javaLibrary.getSourcePathToOutput()));

    BuildTarget target = BuildTargetFactory.newInstance("//:library-abi");
    CalculateClassAbi calculateAbi =
        CalculateClassAbi.of(
            target,
            ruleFinder,
            filesystem,
            builder.createBuildRuleParams(graphBuilder),
            DefaultBuildTargetSourcePath.of(javaLibraryTarget));

    FileHashCache initialHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    DefaultRuleKeyFactory initialRuleKeyFactory =
        new TestDefaultRuleKeyFactory(initialHashCache, pathResolver, ruleFinder);
    RuleKey initialKey = initialRuleKeyFactory.build(calculateAbi);
    RuleKey initialInputKey =
        new TestInputBasedRuleKeyFactory(initialHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("new stuff", input);
    filesystem.writeContentsToPath(
        "new stuff", pathResolver.getRelativePath(javaLibrary.getSourcePathToOutput()));

    // Re-setup some entities to drop internal rule key caching.
    graphBuilder = new TestActionGraphBuilder();
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    builder.build(graphBuilder, filesystem);

    FileHashCache alteredHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    DefaultRuleKeyFactory alteredRuleKeyFactory =
        new TestDefaultRuleKeyFactory(alteredHashCache, pathResolver, ruleFinder);
    RuleKey alteredKey = alteredRuleKeyFactory.build(calculateAbi);
    RuleKey alteredInputKey =
        new TestInputBasedRuleKeyFactory(alteredHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    assertThat(initialKey, Matchers.not(Matchers.equalTo(alteredKey)));
    assertThat(initialInputKey, Matchers.not(Matchers.equalTo(alteredInputKey)));
  }

  @Test
  public void inputRuleKeyDoesNotChangeIfGeneratedBinaryJarDoesNotChange() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the initial java library.
    Path input = Paths.get("input.java");
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:library");
    JavaLibraryBuilder builder =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(PathSourcePath.of(filesystem, input));
    DefaultJavaLibrary javaLibrary = builder.build(graphBuilder, filesystem);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("stuff", input);
    filesystem.writeContentsToPath(
        "stuff", pathResolver.getRelativePath(javaLibrary.getSourcePathToOutput()));

    BuildTarget target = BuildTargetFactory.newInstance("//:library-abi");
    CalculateClassAbi calculateAbi =
        CalculateClassAbi.of(
            target,
            ruleFinder,
            filesystem,
            builder.createBuildRuleParams(graphBuilder),
            DefaultBuildTargetSourcePath.of(javaLibraryTarget));

    FileHashCache initialHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    DefaultRuleKeyFactory initialRuleKeyFactory =
        new TestDefaultRuleKeyFactory(initialHashCache, pathResolver, ruleFinder);
    RuleKey initialKey = initialRuleKeyFactory.build(calculateAbi);
    RuleKey initialInputKey =
        new TestInputBasedRuleKeyFactory(initialHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("new stuff", input);

    // Re-setup some entities to drop internal rule key caching.
    graphBuilder = new TestActionGraphBuilder();
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    builder.build(graphBuilder, filesystem);

    FileHashCache alteredHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    DefaultRuleKeyFactory alteredRuleKeyFactory =
        new TestDefaultRuleKeyFactory(alteredHashCache, pathResolver, ruleFinder);
    RuleKey alteredKey = alteredRuleKeyFactory.build(calculateAbi);
    RuleKey alteredInputKey =
        new TestInputBasedRuleKeyFactory(alteredHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    assertThat(initialKey, Matchers.not(Matchers.equalTo(alteredKey)));
    assertThat(initialInputKey, Matchers.equalTo(alteredInputKey));
  }
}

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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CalculateAbiFromClassesTest {

  @Test
  public void testIsAbiTargetRecognizesAbiTargets() {
    assertTrue(
        HasJavaAbi.isClassAbiTarget(BuildTargetFactory.newInstance("//foo/bar:bar#class-abi")));
  }

  @Test
  public void testIsAbiTargetRecognizesNonAbiTargets() {
    assertFalse(
        HasJavaAbi.isClassAbiTarget(BuildTargetFactory.newInstance("//foo/bar:bar#not-abi")));
  }

  @Test
  public void testGetLibraryTarget() {
    assertThat(
        HasJavaAbi.getLibraryTarget(BuildTargetFactory.newInstance("//foo/bar:bar#class-abi")),
        Matchers.equalTo(BuildTargetFactory.newInstance("//foo/bar:bar")));
  }

  @Test
  public void ruleKeysChangeIfGeneratedBinaryJarChanges() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the initial java library.
    Path input = Paths.get("input.java");
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:library");
    JavaLibraryBuilder builder =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(new PathSourcePath(filesystem, input));
    DefaultJavaLibrary javaLibrary = builder.build(resolver, filesystem);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("stuff", input);
    filesystem.writeContentsToPath(
        "stuff", pathResolver.getRelativePath(javaLibrary.getSourcePathToOutput()));

    BuildTarget target = BuildTargetFactory.newInstance("//:library-abi");
    CalculateAbiFromClasses calculateAbi =
        CalculateAbiFromClasses.of(
            target,
            ruleFinder,
            builder.createBuildRuleParams(resolver, filesystem),
            new DefaultBuildTargetSourcePath(javaLibraryTarget));

    FileHashCache initialHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    DefaultRuleKeyFactory initialRuleKeyFactory =
        new DefaultRuleKeyFactory(0, initialHashCache, pathResolver, ruleFinder);
    RuleKey initialKey = initialRuleKeyFactory.build(calculateAbi);
    RuleKey initialInputKey =
        new InputBasedRuleKeyFactory(0, initialHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("new stuff", input);
    filesystem.writeContentsToPath(
        "new stuff", pathResolver.getRelativePath(javaLibrary.getSourcePathToOutput()));

    // Re-setup some entities to drop internal rule key caching.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    builder.build(resolver, filesystem);

    FileHashCache alteredHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    DefaultRuleKeyFactory alteredRuleKeyFactory =
        new DefaultRuleKeyFactory(0, alteredHashCache, pathResolver, ruleFinder);
    RuleKey alteredKey = alteredRuleKeyFactory.build(calculateAbi);
    RuleKey alteredInputKey =
        new InputBasedRuleKeyFactory(0, alteredHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    assertThat(initialKey, Matchers.not(Matchers.equalTo(alteredKey)));
    assertThat(initialInputKey, Matchers.not(Matchers.equalTo(alteredInputKey)));
  }

  @Test
  public void inputRuleKeyDoesNotChangeIfGeneratedBinaryJarDoesNotChange() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the initial java library.
    Path input = Paths.get("input.java");
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:library");
    JavaLibraryBuilder builder =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(new PathSourcePath(filesystem, input));
    DefaultJavaLibrary javaLibrary = builder.build(resolver, filesystem);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("stuff", input);
    filesystem.writeContentsToPath(
        "stuff", pathResolver.getRelativePath(javaLibrary.getSourcePathToOutput()));

    BuildTarget target = BuildTargetFactory.newInstance("//:library-abi");
    CalculateAbiFromClasses calculateAbi =
        CalculateAbiFromClasses.of(
            target,
            ruleFinder,
            builder.createBuildRuleParams(resolver, filesystem),
            new DefaultBuildTargetSourcePath(javaLibraryTarget));

    FileHashCache initialHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    DefaultRuleKeyFactory initialRuleKeyFactory =
        new DefaultRuleKeyFactory(0, initialHashCache, pathResolver, ruleFinder);
    RuleKey initialKey = initialRuleKeyFactory.build(calculateAbi);
    RuleKey initialInputKey =
        new InputBasedRuleKeyFactory(0, initialHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("new stuff", input);

    // Re-setup some entities to drop internal rule key caching.
    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(resolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    builder.build(resolver, filesystem);

    FileHashCache alteredHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    DefaultRuleKeyFactory alteredRuleKeyFactory =
        new DefaultRuleKeyFactory(0, alteredHashCache, pathResolver, ruleFinder);
    RuleKey alteredKey = alteredRuleKeyFactory.build(calculateAbi);
    RuleKey alteredInputKey =
        new InputBasedRuleKeyFactory(0, alteredHashCache, pathResolver, ruleFinder)
            .build(calculateAbi);

    assertThat(initialKey, Matchers.not(Matchers.equalTo(alteredKey)));
    assertThat(initialInputKey, Matchers.equalTo(alteredInputKey));
  }
}

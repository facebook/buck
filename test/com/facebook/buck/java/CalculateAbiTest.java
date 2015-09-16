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

package com.facebook.buck.java;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CalculateAbiTest {

  @Test
  public void ruleKeysChangeIfGeneratedBinaryJarChanges() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the initial java library.
    Path input = Paths.get("input.java");
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:library");
    JavaLibraryBuilder builder =  JavaLibraryBuilder.createBuilder(javaLibraryTarget)
        .addSrc(new PathSourcePath(filesystem, input));
    DefaultJavaLibrary javaLibrary = (DefaultJavaLibrary) builder.build(resolver, filesystem);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("stuff", input);
    filesystem.writeContentsToPath("stuff", javaLibrary.getPathToOutput());

    BuildTarget target = BuildTargetFactory.newInstance("//:library-abi");
    CalculateAbi calculateAbi =
        CalculateAbi.of(
            target,
            pathResolver,
            builder.createBuildRuleParams(resolver, filesystem),
            new BuildTargetSourcePath(javaLibraryTarget));

    RuleKey initialKey =
        new DefaultRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();
    RuleKey initialInputKey =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("new stuff", input);
    filesystem.writeContentsToPath("new stuff", javaLibrary.getPathToOutput());

    // Re-setup some entities to drop internal rule key caching.
    resolver = new BuildRuleResolver();
    pathResolver = new SourcePathResolver(resolver);
    builder.build(resolver, filesystem);

    RuleKey alteredKey =
        new DefaultRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();
    RuleKey alteredInputKey =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();

    assertThat(initialKey, Matchers.not(Matchers.equalTo(alteredKey)));
    assertThat(initialInputKey, Matchers.not(Matchers.equalTo(alteredInputKey)));
  }

  @Test
  public void inputRuleKeyDoesNotChangeIfGeneratedBinaryJarDoesNotChange() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    // Setup the initial java library.
    Path input = Paths.get("input.java");
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:library");
    JavaLibraryBuilder builder =  JavaLibraryBuilder.createBuilder(javaLibraryTarget)
        .addSrc(new PathSourcePath(filesystem, input));
    DefaultJavaLibrary javaLibrary = (DefaultJavaLibrary) builder.build(resolver, filesystem);

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("stuff", input);
    filesystem.writeContentsToPath("stuff", javaLibrary.getPathToOutput());

    BuildTarget target = BuildTargetFactory.newInstance("//:library-abi");
    CalculateAbi calculateAbi =
        CalculateAbi.of(
            target,
            pathResolver,
            builder.createBuildRuleParams(resolver, filesystem),
            new BuildTargetSourcePath(javaLibraryTarget));

    RuleKey initialKey =
        new DefaultRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();
    RuleKey initialInputKey =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();

    // Write something to the library source and geneated JAR, so they exist to generate rule keys.
    filesystem.writeContentsToPath("new stuff", input);

    // Re-setup some entities to drop internal rule key caching.
    resolver = new BuildRuleResolver();
    pathResolver = new SourcePathResolver(resolver);
    builder.build(resolver, filesystem);

    RuleKey alteredKey =
        new DefaultRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();
    RuleKey alteredInputKey =
        new InputBasedRuleKeyBuilderFactory(
            new DefaultFileHashCache(filesystem),
            pathResolver)
            .newInstance(calculateAbi).build();

    assertThat(initialKey, Matchers.not(Matchers.equalTo(alteredKey)));
    assertThat(initialInputKey, Matchers.equalTo(alteredInputKey));
  }

}

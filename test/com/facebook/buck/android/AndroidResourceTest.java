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
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidResourceTest {

  @Test
  public void testRuleKeyForDifferentInputFilenames() throws Exception {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/src/com/facebook/base:res");
    Function<Path, BuildRuleResolver> createResourceRule =
        (Path resourcePath) -> {
          FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
          projectFilesystem.createNewFile(resourcePath);
          projectFilesystem.createNewFile(
              Paths.get("java/src/com/facebook/base/assets/drawable/B.xml"));

          TargetNode<?, ?> resourceNode =
              AndroidResourceBuilder.createBuilder(buildTarget, projectFilesystem)
                  .setRes(new FakeSourcePath(projectFilesystem, "java/src/com/facebook/base/res"))
                  .setRDotJavaPackage("com.facebook")
                  .setAssets(
                      new FakeSourcePath(projectFilesystem, "java/src/com/facebook/base/assets"))
                  .setManifest(
                      new PathSourcePath(
                          projectFilesystem,
                          Paths.get("java/src/com/facebook/base/AndroidManifest.xml")))
                  .build();

          TargetGraph targetGraph = TargetGraphFactory.newInstance(resourceNode);
          return new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
        };

    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.of(
                "java/src/com/facebook/base/AndroidManifest.xml", "bbbbbbbbbb",
                "java/src/com/facebook/base/assets/drawable/B.xml", "aaaaaaaaaaaa",
                "java/src/com/facebook/base/res/drawable/A.xml", "dddddddddd",
                "java/src/com/facebook/base/res/drawable/C.xml", "eeeeeeeeee"));

    BuildRuleResolver resolver1 =
        createResourceRule.apply(Paths.get("java/src/com/facebook/base/res/drawable/A.xml"));
    BuildRuleResolver resolver2 =
        createResourceRule.apply(Paths.get("java/src/com/facebook/base/res/drawable/C.xml"));

    BuildRule androidResource1 = resolver1.requireRule(buildTarget);
    SourcePathRuleFinder ruleFinder1 = new SourcePathRuleFinder(resolver1);
    SourcePathResolver pathResolver1 = new SourcePathResolver(ruleFinder1);

    BuildRule androidResource2 = resolver2.requireRule(buildTarget);
    SourcePathRuleFinder ruleFinder2 = new SourcePathRuleFinder(resolver2);
    SourcePathResolver pathResolver2 = new SourcePathResolver(ruleFinder2);

    RuleKey ruleKey1 =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver1, ruleFinder1).build(androidResource1);
    RuleKey ruleKey2 =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver2, ruleFinder2).build(androidResource2);

    assertNotEquals(
        "The two android_resource rules should have different rule keys.", ruleKey1, ruleKey2);
  }

  @Test
  public void testGetRDotJavaPackageWhenPackageIsSpecified() throws IOException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            projectFilesystem.getRootPath(), "//java/src/com/facebook/base:res");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(buildTarget).setProjectFilesystem(projectFilesystem).build();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    AndroidResource androidResource =
        new AndroidResource(
            params,
            ruleFinder,
            /* deps */ ImmutableSortedSet.of(),
            new FakeSourcePath("foo/res"),
            ImmutableSortedMap.of(
                Paths.get("values/strings.xml"), new FakeSourcePath("foo/res/values/strings.xml")),
            /* rDotJavaPackage */ "com.example.android",
            /* assets */ null,
            /* assetsSrcs */ ImmutableSortedMap.of(),
            /* manifestFile */ null,
            /* hasWhitelistedStrings */ false);
    projectFilesystem.writeContentsToPath(
        "com.example.android\n",
        resolver.getRelativePath(androidResource.getPathToRDotJavaPackageFile()));
    FakeOnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    androidResource.initializeFromDisk(onDiskBuildInfo);
    assertEquals("com.example.android", androidResource.getRDotJavaPackage());
  }

  @Test
  public void testGetRDotJavaPackageWhenPackageIsNotSpecified() throws IOException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            projectFilesystem.getRootPath(), "//java/src/com/facebook/base:res");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(buildTarget).setProjectFilesystem(projectFilesystem).build();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    AndroidResource androidResource =
        new AndroidResource(
            params,
            ruleFinder,
            /* deps */ ImmutableSortedSet.of(),
            new FakeSourcePath("foo/res"),
            ImmutableSortedMap.of(
                Paths.get("values/strings.xml"), new FakeSourcePath("foo/res/values/strings.xml")),
            /* rDotJavaPackage */ null,
            /* assets */ null,
            /* assetsSrcs */ ImmutableSortedMap.of(),
            /* manifestFile */ new PathSourcePath(
                projectFilesystem, Paths.get("foo/AndroidManifest.xml")),
            /* hasWhitelistedStrings */ false);
    projectFilesystem.writeContentsToPath(
        "com.ex.pkg\n", resolver.getRelativePath(androidResource.getPathToRDotJavaPackageFile()));
    FakeOnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    androidResource.initializeFromDisk(onDiskBuildInfo);
    assertEquals("com.ex.pkg", androidResource.getRDotJavaPackage());
  }

  @Test
  public void testInputRuleKeyChangesIfDependencySymbolsChanges() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    TargetNode<?, ?> depNode =
        AndroidResourceBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"), filesystem)
            .setManifest(new FakeSourcePath("manifest"))
            .setRes(Paths.get("res"))
            .build();
    TargetNode<?, ?> resourceNode =
        AndroidResourceBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"), filesystem)
            .setDeps(ImmutableSortedSet.of(depNode.getBuildTarget()))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, resourceNode);
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    AndroidResource dep = (AndroidResource) resolver.requireRule(depNode.getBuildTarget());
    AndroidResource resource =
        (AndroidResource) resolver.requireRule(resourceNode.getBuildTarget());

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getPathToTextSymbolsFile()));
    RuleKey original =
        new InputBasedRuleKeyFactory(0, fileHashCache, pathResolver, ruleFinder).build(resource);

    fileHashCache.invalidateAll();

    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getPathToTextSymbolsFile()));
    RuleKey changed =
        new InputBasedRuleKeyFactory(0, fileHashCache, pathResolver, ruleFinder).build(resource);

    assertThat(original, Matchers.not(Matchers.equalTo(changed)));
  }
}

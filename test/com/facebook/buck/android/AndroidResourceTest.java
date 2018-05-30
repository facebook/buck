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

import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
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
  public void testRuleKeyForDifferentInputFilenames() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/src/com/facebook/base:res");
    Function<Path, ActionGraphBuilder> createResourceRule =
        (Path resourcePath) -> {
          FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
          projectFilesystem.createNewFile(resourcePath);
          projectFilesystem.createNewFile(
              Paths.get("java/src/com/facebook/base/assets/drawable/B.xml"));

          TargetNode<?, ?> resourceNode =
              AndroidResourceBuilder.createBuilder(buildTarget, projectFilesystem)
                  .setRes(FakeSourcePath.of(projectFilesystem, "java/src/com/facebook/base/res"))
                  .setRDotJavaPackage("com.facebook")
                  .setAssets(
                      FakeSourcePath.of(projectFilesystem, "java/src/com/facebook/base/assets"))
                  .setManifest(
                      FakeSourcePath.of(
                          projectFilesystem, "java/src/com/facebook/base/AndroidManifest.xml"))
                  .build();

          TargetGraph targetGraph = TargetGraphFactory.newInstance(resourceNode);
          return new TestActionGraphBuilder(targetGraph);
        };

    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.of(
                "java/src/com/facebook/base/AndroidManifest.xml", "bbbbbbbbbb",
                "java/src/com/facebook/base/assets/drawable/B.xml", "aaaaaaaaaaaa",
                "java/src/com/facebook/base/res/drawable/A.xml", "dddddddddd",
                "java/src/com/facebook/base/res/drawable/C.xml", "eeeeeeeeee"));

    ActionGraphBuilder builder1 =
        createResourceRule.apply(Paths.get("java/src/com/facebook/base/res/drawable/A.xml"));
    ActionGraphBuilder builder2 =
        createResourceRule.apply(Paths.get("java/src/com/facebook/base/res/drawable/C.xml"));

    BuildRule androidResource1 = builder1.requireRule(buildTarget);
    SourcePathRuleFinder ruleFinder1 = new SourcePathRuleFinder(builder1);
    SourcePathResolver pathResolver1 = DefaultSourcePathResolver.from(ruleFinder1);

    BuildRule androidResource2 = builder2.requireRule(buildTarget);
    SourcePathRuleFinder ruleFinder2 = new SourcePathRuleFinder(builder2);
    SourcePathResolver pathResolver2 = DefaultSourcePathResolver.from(ruleFinder2);

    RuleKey ruleKey1 =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver1, ruleFinder1)
            .build(androidResource1);
    RuleKey ruleKey2 =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver2, ruleFinder2)
            .build(androidResource2);

    assertNotEquals(
        "The two android_resource rules should have different rule keys.", ruleKey1, ruleKey2);
  }

  @Test
  public void testGetRDotJavaPackageWhenPackageIsSpecified() throws IOException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            projectFilesystem.getRootPath(), "//java/src/com/facebook/base:res");
    BuildRuleParams params = TestBuildRuleParams.create();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    AndroidResource androidResource =
        new AndroidResource(
            buildTarget,
            projectFilesystem,
            params,
            ruleFinder,
            /* deps */ ImmutableSortedSet.of(),
            FakeSourcePath.of("foo/res"),
            ImmutableSortedMap.of(
                Paths.get("values/strings.xml"), FakeSourcePath.of("foo/res/values/strings.xml")),
            /* rDotJavaPackage */ "com.example.android",
            /* assets */ null,
            /* assetsSrcs */ ImmutableSortedMap.of(),
            /* manifestFile */ null,
            /* hasWhitelistedStrings */ false);
    projectFilesystem.writeContentsToPath(
        "com.example.android\n",
        resolver.getRelativePath(androidResource.getPathToRDotJavaPackageFile()));
    androidResource.initializeFromDisk();
    assertEquals("com.example.android", androidResource.getRDotJavaPackage());
  }

  @Test
  public void testGetRDotJavaPackageWhenPackageIsNotSpecified() throws IOException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(
            projectFilesystem.getRootPath(), "//java/src/com/facebook/base:res");
    BuildRuleParams params = TestBuildRuleParams.create();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    AndroidResource androidResource =
        new AndroidResource(
            buildTarget,
            projectFilesystem,
            params,
            ruleFinder,
            /* deps */ ImmutableSortedSet.of(),
            FakeSourcePath.of("foo/res"),
            ImmutableSortedMap.of(
                Paths.get("values/strings.xml"), FakeSourcePath.of("foo/res/values/strings.xml")),
            /* rDotJavaPackage */ null,
            /* assets */ null,
            /* assetsSrcs */ ImmutableSortedMap.of(),
            /* manifestFile */ FakeSourcePath.of(projectFilesystem, "foo/AndroidManifest.xml"),
            /* hasWhitelistedStrings */ false);
    projectFilesystem.writeContentsToPath(
        "com.ex.pkg\n", resolver.getRelativePath(androidResource.getPathToRDotJavaPackageFile()));
    androidResource.initializeFromDisk();
    assertEquals("com.ex.pkg", androidResource.getRDotJavaPackage());
  }

  @Test
  public void testInputRuleKeyChangesIfDependencySymbolsChanges() throws Exception {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    TargetNode<?, ?> depNode =
        AndroidResourceBuilder.createBuilder(BuildTargetFactory.newInstance("//:dep"), filesystem)
            .setManifest(FakeSourcePath.of("manifest"))
            .setRes(Paths.get("res"))
            .build();
    TargetNode<?, ?> resourceNode =
        AndroidResourceBuilder.createBuilder(BuildTargetFactory.newInstance("//:rule"), filesystem)
            .setDeps(ImmutableSortedSet.of(depNode.getBuildTarget()))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, resourceNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    AndroidResource dep = (AndroidResource) graphBuilder.requireRule(depNode.getBuildTarget());
    AndroidResource resource =
        (AndroidResource) graphBuilder.requireRule(resourceNode.getBuildTarget());

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FileHashCache fileHashCache =
        StackedFileHashCache.createDefaultHashCaches(filesystem, FileHashCacheMode.DEFAULT);
    filesystem.writeContentsToPath(
        "something", pathResolver.getRelativePath(dep.getPathToTextSymbolsFile()));
    RuleKey original =
        new TestInputBasedRuleKeyFactory(fileHashCache, pathResolver, ruleFinder).build(resource);

    fileHashCache.invalidateAll();

    filesystem.writeContentsToPath(
        "something else", pathResolver.getRelativePath(dep.getPathToTextSymbolsFile()));
    RuleKey changed =
        new TestInputBasedRuleKeyFactory(fileHashCache, pathResolver, ruleFinder).build(resource);

    assertThat(original, Matchers.not(Matchers.equalTo(changed)));
  }
}

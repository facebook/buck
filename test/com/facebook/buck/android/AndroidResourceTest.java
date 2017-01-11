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
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;


public class AndroidResourceTest {

  @Test
  public void testRuleKeyForDifferentInputFilenames() throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/src/com/facebook/base:res");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();

    AndroidResource androidResource1 = AndroidResourceRuleBuilder.newBuilder()
        .setResolver(pathResolver)
        .setRuleFinder(ruleFinder)
        .setBuildRuleParams(params)
        .setRes(new FakeSourcePath("java/src/com/facebook/base/res"))
        .setResSrcs(
            ImmutableSortedSet.of(
                new FakeSourcePath(
                    params.getProjectFilesystem(),
                    "java/src/com/facebook/base/res/drawable/A.xml")))
        .setRDotJavaPackage("com.facebook")
        .setAssets(new FakeSourcePath("java/src/com/facebook/base/assets"))
        .setAssetsSrcs(
            ImmutableSortedSet.of(
                new FakeSourcePath(
                    params.getProjectFilesystem(),
                    "java/src/com/facebook/base/assets/drawable/B.xml")))
        .setManifest(
            new PathSourcePath(
                projectFilesystem,
                Paths.get("java/src/com/facebook/base/AndroidManifest.xml")))
        .build();

    AndroidResource androidResource2 = AndroidResourceRuleBuilder.newBuilder()
        .setResolver(pathResolver)
        .setRuleFinder(ruleFinder)
        .setBuildRuleParams(params)
        .setRes(new FakeSourcePath("java/src/com/facebook/base/res"))
        .setResSrcs(
            ImmutableSortedSet.of(
                new FakeSourcePath(
                    params.getProjectFilesystem(),
                    "java/src/com/facebook/base/res/drawable/C.xml")))
        .setRDotJavaPackage("com.facebook")
        .setAssets(new FakeSourcePath("java/src/com/facebook/base/assets"))
        .setAssetsSrcs(
            ImmutableSortedSet.of(
                new FakeSourcePath(
                    params.getProjectFilesystem(),
                    "java/src/com/facebook/base/assets/drawable/B.xml")))
        .setManifest(
            new PathSourcePath(
                projectFilesystem,
                Paths.get("java/src/com/facebook/base/AndroidManifest.xml")))
        .build();

    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.of(
            "java/src/com/facebook/base/AndroidManifest.xml", "bbbbbbbbbb",
            "java/src/com/facebook/base/assets/drawable/A.xml", "cccccccccccc",
            "java/src/com/facebook/base/assets/drawable/B.xml", "aaaaaaaaaaaa",
            "java/src/com/facebook/base/res/drawable/A.xml", "dddddddddd",
            "java/src/com/facebook/base/res/drawable/C.xml", "eeeeeeeeee"
        ));
    RuleKey ruleKey1 = new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
        .build(androidResource1);
    RuleKey ruleKey2 = new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
        .build(androidResource2);

    assertNotEquals(
        "The two android_resource rules should have different rule keys.",
        ruleKey1,
        ruleKey2);
  }

  @Test
  public void testGetRDotJavaPackageWhenPackageIsSpecified() throws IOException {
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createRealTempFilesystem();
    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        projectFilesystem,
        "//java/src/com/facebook/base:res");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget)
        .setProjectFilesystem(projectFilesystem)
        .build();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new DefaultTargetNodeToBuildRuleTransformer())
    );
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    AndroidResource androidResource = new AndroidResource(
        params,
        resolver,
        ruleFinder,
        /* deps */ ImmutableSortedSet.of(),
        new FakeSourcePath("foo/res"),
        ImmutableSortedSet.of((SourcePath) new FakeSourcePath("foo/res/values/strings.xml")),
        Optional.empty(),
        /* rDotJavaPackage */ "com.example.android",
        /* assets */ null,
        /* assetsSrcs */ ImmutableSortedSet.of(),
        Optional.empty(),
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
    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        projectFilesystem,
        "//java/src/com/facebook/base:res");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget)
        .setProjectFilesystem(projectFilesystem)
        .build();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(
        new BuildRuleResolver(
            TargetGraph.EMPTY,
            new DefaultTargetNodeToBuildRuleTransformer())
    );
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    AndroidResource androidResource = new AndroidResource(
        params,
        resolver,
        ruleFinder,
        /* deps */ ImmutableSortedSet.of(),
        new FakeSourcePath("foo/res"),
        ImmutableSortedSet.of((SourcePath) new FakeSourcePath("foo/res/values/strings.xml")),
        Optional.empty(),
        /* rDotJavaPackage */ null,
        /* assets */ null,
        /* assetsSrcs */ ImmutableSortedSet.of(),
        Optional.empty(),
        /* manifestFile */ new PathSourcePath(
            projectFilesystem,
            Paths.get("foo/AndroidManifest.xml")),
        /* hasWhitelistedStrings */ false);
    projectFilesystem.writeContentsToPath(
        "com.ex.pkg\n",
        resolver.getRelativePath(androidResource.getPathToRDotJavaPackageFile()));
    FakeOnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    androidResource.initializeFromDisk(onDiskBuildInfo);
    assertEquals("com.ex.pkg", androidResource.getRDotJavaPackage());
  }

  @Test
  public void testInputRuleKeyChangesIfDependencySymbolsChanges() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache fileHashCache = DefaultFileHashCache.createDefaultFileHashCache(filesystem);
    AndroidResource dep =
        (AndroidResource) AndroidResourceBuilder
            .createBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setManifest(new FakeSourcePath("manifest"))
            .setRes(Paths.get("res"))
            .build(resolver, filesystem);
    AndroidResource resource =
        (AndroidResource) AndroidResourceBuilder
            .createBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setDeps(ImmutableSortedSet.of(dep.getBuildTarget()))
            .build(resolver, filesystem);

    filesystem.writeContentsToPath(
        "something",
        pathResolver.getRelativePath(dep.getPathToTextSymbolsFile()));
    RuleKey original = new InputBasedRuleKeyFactory(
        0,
        fileHashCache,
        pathResolver,
        ruleFinder)
        .build(resource);

    fileHashCache.invalidateAll();

    filesystem.writeContentsToPath(
        "something else",
        pathResolver.getRelativePath(dep.getPathToTextSymbolsFile()));
    RuleKey changed = new InputBasedRuleKeyFactory(
        0,
        fileHashCache,
        pathResolver,
        ruleFinder)
        .build(resource);

    assertThat(original, Matchers.not(Matchers.equalTo(changed)));
  }
}

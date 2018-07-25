/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class AaptPackageResourcesTest {

  private ActionGraphBuilder graphBuilder;
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolver pathResolver;
  private BuildTarget aaptTarget;
  private FakeProjectFilesystem filesystem;

  private AndroidResource resource1;
  private AndroidResource resource2;

  private FakeFileHashCache hashCache;

  SourcePath createPathSourcePath(String path, String contentForHash) {
    hashCache.set(filesystem.resolve(path), HashCode.fromInt(contentForHash.hashCode()));
    return FakeSourcePath.of(filesystem, path);
  }

  FilteredResourcesProvider createIdentifyResourcesProvider(String... paths) {
    return new IdentityResourcesProvider(
        RichStream.from(paths)
            .map(p -> (SourcePath) FakeSourcePath.of(filesystem, p))
            .toImmutableList());
  }

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    filesystem = new FakeProjectFilesystem();

    TargetNode<?> resourceNode =
        AndroidResourceBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:resource1"), filesystem)
            .setRDotJavaPackage("package1")
            .setRes(Paths.get("res1"))
            .setAssets(FakeSourcePath.of(filesystem, "asset1"))
            .build();

    TargetNode<?> resourceNode2 =
        AndroidResourceBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:resource2"), filesystem)
            .setRDotJavaPackage("package2")
            .setRes(Paths.get("res2"))
            .setAssets(FakeSourcePath.of(filesystem, "asset2"))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(resourceNode, resourceNode2);
    graphBuilder = new TestActionGraphBuilder(targetGraph);
    resource1 = (AndroidResource) graphBuilder.requireRule(resourceNode.getBuildTarget());
    resource2 = (AndroidResource) graphBuilder.requireRule(resourceNode2.getBuildTarget());

    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    aaptTarget = BuildTargetFactory.newInstance("//foo:bar");

    hashCache = new FakeFileHashCache(new HashMap<>());
    createPathSourcePath("res1", "resources1");
    createPathSourcePath("res2", "resources2");
    createPathSourcePath("res3", "resources3");
    createPathSourcePath("asset1", "assets1");
    createPathSourcePath("asset2", "assets2");
    createPathSourcePath("justAssets", "justAssets");
  }

  class AaptConstructorArgs {
    SourcePath manifest;
    FilteredResourcesProvider filteredResourcesProvider;
    ImmutableList<HasAndroidResourceDeps> hasAndroidResourceDeps;
    ManifestEntries manifestEntries;

    AaptConstructorArgs() {
      manifest = createPathSourcePath("AndroidManifest.xml", "content");
      filteredResourcesProvider = new IdentityResourcesProvider(ImmutableList.of());
      hasAndroidResourceDeps = ImmutableList.of();
      manifestEntries = ManifestEntries.empty();
    }
  }

  @Test
  public void testThatChangingAndroidManifestChangesRuleKey() {
    // Generate a rule key for the defaults.
    AaptConstructorArgs args = new AaptConstructorArgs();

    args.manifest = createPathSourcePath("AndroidManifest.xml", "same_content");
    RuleKey previousRuleKey = calculateRuleKey(args);

    args.manifest = createPathSourcePath("other/AndroidManifest.xml", "same_content");
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.manifest = createPathSourcePath("other/AndroidManifest.xml", "different_content");
    previousRuleKey = assertKeyChanged(previousRuleKey, args);
  }

  @Test
  public void testThatAddingResourceDepChangesRuleKey() {
    // Generate a rule key for the defaults.
    AaptConstructorArgs args = new AaptConstructorArgs();

    RuleKey previousRuleKey = calculateRuleKey(args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource1);
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource2);
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource1, resource2);
    previousRuleKey = assertKeyChanged(previousRuleKey, args);
  }

  @Test
  public void testThatChangingResourceDirectoryOrderChangesRulekey() {
    // Generate a rule key for the defaults.
    AaptConstructorArgs args = new AaptConstructorArgs();

    RuleKey previousRuleKey = calculateRuleKey(args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource1, resource2);
    args.filteredResourcesProvider = createIdentifyResourcesProvider("res1", "res2");
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource1, resource2);
    args.filteredResourcesProvider = createIdentifyResourcesProvider("res2", "res1");

    // TODO(cjhopman): AaptPackageResources' rulekey doesn't properly reflect changes in the
    // ordering of resource-only dependencies.
    // previousRuleKey = assertKeyChanged(previousRuleKey, args);
  }

  @Test
  public void testThatChangingResourcesChangesRuleKey() {
    // Generate a rule key for the defaults.
    AaptConstructorArgs args = new AaptConstructorArgs();

    RuleKey previousRuleKey = calculateRuleKey(args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource1);
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    createPathSourcePath("res1", "different_value");
    previousRuleKey = assertKeyChanged(previousRuleKey, args);
  }

  @Test
  public void testThatChangingFilteredResourcesProviderChangesRuleKey() {
    // Generate a rule key for the defaults.
    AaptConstructorArgs args = new AaptConstructorArgs();

    RuleKey previousRuleKey = calculateRuleKey(args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource1, resource2);
    args.filteredResourcesProvider = createIdentifyResourcesProvider("res1", "res2");
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.filteredResourcesProvider =
        new ResourcesFilter(
            aaptTarget.withFlavors(InternalFlavor.of("filter")),
            filesystem,
            ImmutableSortedSet.of(resource1, resource2),
            ImmutableSortedSet.of(resource1, resource2),
            ruleFinder,
            ImmutableList.of(resource1.getRes(), resource2.getRes()),
            ImmutableSet.of(),
            ImmutableSet.of(),
            /* localizedStringFileName */ null,
            ResourcesFilter.ResourceCompressionMode.DISABLED,
            FilterResourcesSteps.ResourceFilter.EMPTY_FILTER,
            Optional.empty());

    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.filteredResourcesProvider =
        new ResourcesFilter(
            aaptTarget.withFlavors(InternalFlavor.of("filter")),
            filesystem,
            ImmutableSortedSet.of(resource1, resource2),
            ImmutableSortedSet.of(resource1, resource2),
            ruleFinder,
            ImmutableList.of(resource1.getRes(), resource2.getRes()),
            ImmutableSet.of(),
            ImmutableSet.of("some_locale"),
            /* localizedStringFileName */ null,
            ResourcesFilter.ResourceCompressionMode.DISABLED,
            FilterResourcesSteps.ResourceFilter.EMPTY_FILTER,
            Optional.empty());

    previousRuleKey = assertKeyChanged(previousRuleKey, args);
  }

  @Test
  public void testThatChangingManifestEntriesChangesRuleKey() {
    // Generate a rule key for the defaults.
    AaptConstructorArgs args = new AaptConstructorArgs();

    args.manifestEntries = ManifestEntries.builder().setDebugMode(false).build();
    RuleKey previousRuleKey = calculateRuleKey(args);

    args.manifestEntries = ManifestEntries.builder().setDebugMode(true).build();
    previousRuleKey = assertKeyChanged(previousRuleKey, args);
  }

  private RuleKey assertKeyChanged(RuleKey previousKey, AaptConstructorArgs args) {
    RuleKey newKey = calculateRuleKey(args);
    assertNotEquals(previousKey, newKey);
    return newKey;
  }

  private RuleKey calculateRuleKey(AaptConstructorArgs constructorArgs) {
    return new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
        .build(
            new AaptPackageResources(
                aaptTarget,
                filesystem,
                TestAndroidPlatformTargetFactory.create(),
                ruleFinder,
                graphBuilder,
                constructorArgs.manifest,
                ImmutableList.of(),
                constructorArgs.filteredResourcesProvider,
                constructorArgs.hasAndroidResourceDeps,
                false,
                false,
                constructorArgs.manifestEntries));
  }
}

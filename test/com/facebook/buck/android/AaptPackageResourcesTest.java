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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Suppliers;
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

  private BuildRuleResolver ruleResolver;
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolver pathResolver;
  private BuildTarget aaptTarget;
  private BuildRuleParams params;
  private FakeProjectFilesystem filesystem;

  private AndroidResource resource1;
  private AndroidResource resource2;

  private FakeFileHashCache hashCache;

  SourcePath createPathSourcePath(String path, String contentForHash) {
    hashCache.set(filesystem.resolve(path), HashCode.fromInt(contentForHash.hashCode()));
    return new FakeSourcePath(filesystem, path);
  }

  @Before
  public void setUp() throws NoSuchBuildTargetException {
    filesystem = new FakeProjectFilesystem();

    TargetNode<?, ?> resourceNode =
        AndroidResourceBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:resource1"), filesystem)
            .setRDotJavaPackage("package1")
            .setRes(Paths.get("res1"))
            .setAssets(new PathSourcePath(filesystem, Paths.get("asset1")))
            .build();

    TargetNode<?, ?> resourceNode2 =
        AndroidResourceBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:resource2"), filesystem)
            .setRDotJavaPackage("package2")
            .setRes(Paths.get("res2"))
            .setAssets(new PathSourcePath(filesystem, Paths.get("asset2")))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(resourceNode, resourceNode2);
    ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    resource1 = (AndroidResource) ruleResolver.requireRule(resourceNode.getBuildTarget());
    resource2 = (AndroidResource) ruleResolver.requireRule(resourceNode2.getBuildTarget());

    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);
    aaptTarget = BuildTargetFactory.newInstance("//foo:bar");
    params = new FakeBuildRuleParamsBuilder(aaptTarget).build();

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
    AndroidBinary.PackageType packageType;
    ManifestEntries manifestEntries;

    AaptConstructorArgs() {
      manifest = createPathSourcePath("AndroidManifest.xml", "content");
      filteredResourcesProvider = new IdentityResourcesProvider(ImmutableList.of());
      hasAndroidResourceDeps = ImmutableList.of();
      packageType = AndroidBinary.PackageType.DEBUG;
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
    args.filteredResourcesProvider =
        new IdentityResourcesProvider(ImmutableList.of(Paths.get("res1"), Paths.get("res2")));
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.hasAndroidResourceDeps = ImmutableList.of(resource1, resource2);
    args.filteredResourcesProvider =
        new IdentityResourcesProvider(ImmutableList.of(Paths.get("res2"), Paths.get("res1")));

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
    args.filteredResourcesProvider =
        new IdentityResourcesProvider(ImmutableList.of(Paths.get("res1"), Paths.get("res2")));
    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.filteredResourcesProvider =
        new ResourcesFilter(
            params
                .withBuildTarget(params.getBuildTarget().withFlavors(InternalFlavor.of("filter")))
                .copyReplacingDeclaredAndExtraDeps(
                    Suppliers.ofInstance(ImmutableSortedSet.of(resource1, resource2)),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
            ImmutableList.of(resource1.getRes(), resource2.getRes()),
            ImmutableSet.of(),
            ImmutableSet.of(),
            ResourcesFilter.ResourceCompressionMode.DISABLED,
            FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
            Optional.empty());

    previousRuleKey = assertKeyChanged(previousRuleKey, args);

    args.filteredResourcesProvider =
        new ResourcesFilter(
            params
                .withBuildTarget(params.getBuildTarget().withFlavors(InternalFlavor.of("filter")))
                .copyReplacingDeclaredAndExtraDeps(
                    Suppliers.ofInstance(ImmutableSortedSet.of(resource1, resource2)),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
            ImmutableList.of(resource1.getRes(), resource2.getRes()),
            ImmutableSet.of(),
            ImmutableSet.of("some_locale"),
            ResourcesFilter.ResourceCompressionMode.DISABLED,
            FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
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
    return new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
        .build(
            new AaptPackageResources(
                params,
                ruleFinder,
                ruleResolver,
                constructorArgs.manifest,
                constructorArgs.filteredResourcesProvider,
                constructorArgs.hasAndroidResourceDeps,
                false,
                false,
                constructorArgs.manifestEntries));
  }
}

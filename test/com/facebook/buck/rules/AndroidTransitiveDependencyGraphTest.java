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

package com.facebook.buck.rules;

import static org.easymock.EasyMock.createNiceMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.testutil.RuleMap;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.util.Map;

public class AndroidTransitiveDependencyGraphTest {

  /**
   * This is a regression test to ensure that an additional 1 second startup cost is not
   * re-introduced to fb4a.
   */
  @Test
  public void testFindTransitiveDependencies() {
    // Create an AndroidBinaryRule that transitively depends on two prebuilt JARs. One of the two
    // prebuilt JARs will be listed in the AndroidBinaryRule's no_dx list.
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    PrebuiltJarRule guavaRule = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .setBinaryJar("third_party/guava/guava-10.0.1.jar")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    guavaRule.setIsCached(false);
    buildRuleIndex.put(guavaRule.getFullyQualifiedName(), guavaRule);

    PrebuiltJarRule jsr305Rule = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/jsr-305:jsr-305"))
        .setBinaryJar("third_party/jsr-305/jsr305.jar")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    jsr305Rule.setIsCached(false);
    buildRuleIndex.put(jsr305Rule.getFullyQualifiedName(), jsr305Rule);

    DefaultJavaLibraryRule libraryRule = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:example"))
        .addDep(guavaRule.getFullyQualifiedName())
        .addDep(jsr305Rule.getFullyQualifiedName())
        .build(buildRuleIndex);
    libraryRule.setIsCached(true);
    buildRuleIndex.put(libraryRule.getFullyQualifiedName(), libraryRule);

    AndroidResourceRule manifestRule = AndroidResourceRule.newAndroidResourceRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:res"))
        .setManifestFile("java/src/com/facebook/module/AndroidManifest.xml")
        .setAssetsDirectory("assets/")
        .build(buildRuleIndex);
    manifestRule.setIsCached(false);
    buildRuleIndex.put(manifestRule.getFullyQualifiedName(), manifestRule);

    AndroidBinaryRule binaryRule = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .addDep(libraryRule.getFullyQualifiedName())
        .addDep(manifestRule.getFullyQualifiedName())
        .addClasspathEntryToExcludeFromDex("third_party/guava/guava-10.0.1.jar")
        .setManifest("java/src/com/facebook/AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("java/src/com/facebook/base/keystore.properties")
        .build(buildRuleIndex);
    binaryRule.setIsCached(false);
    buildRuleIndex.put(binaryRule.getFullyQualifiedName(), binaryRule);

    // Verify that the correct transitive dependencies are found.
    DependencyGraph graph = RuleMap.createGraphFromBuildRules(buildRuleIndex);
    AndroidTransitiveDependencies transitiveDeps = binaryRule.findTransitiveDependencies(graph,
        Optional.of(createNiceMock(BuildContext.class)));
    assertEquals(
        "Because guava was passed to no_dx, it should not be in the classpathEntriesToDex list",
        ImmutableSet.of("third_party/jsr-305/jsr305.jar"),
        transitiveDeps.classpathEntriesToDex);
    assertEquals(
        "Because guava was passed to no_dx, it should not be treated as a third-party JAR whose " +
            "resources need to be extracted and repacked in the APK. If this is done, then code in " +
            "the guava-10.0.1.dex.1.jar in the APK's assets/ folder may try to load the resource " +
            "from the APK as a ZipFileEntry rather than as a resource within guava-10.0.1.dex.1.jar. " +
            "Loading a resource in this way could take substantially longer. Specifically, this was " +
            "observed to take over one second longer to load the resource in fb4a. Because the " +
            "resource was loaded on startup, this introduced a substantial regression in the startup " +
            "time for the fb4a app.",
        ImmutableSet.of("third_party/jsr-305/jsr305.jar"),
        transitiveDeps.pathsToThirdPartyJars);
    assertEquals(
        "Because assets directory was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of("assets/"),
        transitiveDeps.assetsDirectories);
    assertEquals(
        "Because manifest file was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of("java/src/com/facebook/module/AndroidManifest.xml"),
        transitiveDeps.manifestFiles);
    assertEquals(ImmutableSet.of(), transitiveDeps.nativeLibsDirectories);
    assertEquals(1, transitiveDeps.uncachedBuildRules.get(BuildRuleType.ANDROID_BINARY).size());
    assertEquals(1, transitiveDeps.uncachedBuildRules.get(BuildRuleType.ANDROID_RESOURCE).size());
    assertEquals(2, transitiveDeps.uncachedBuildRules.get(BuildRuleType.PREBUILT_JAR).size());
    assertFalse(transitiveDeps.uncachedBuildRules.containsKey(BuildRuleType.JAVA_LIBRARY));
  }
}

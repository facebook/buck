/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.apkmodule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.startsWith;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public class APKModuleTest {

  private void verifyDependencies(
      APKModuleGraph graph, APKModule module, ImmutableSet<String> names) {
    ImmutableSet<APKModule> deps = graph.getGraph().getOutgoingNodesFor(module);
    assertThat(deps.size(), is(names.size()));
    for (APKModule dep : deps) {
      assertThat(dep.getName(), in(names));
    }
  }

  @Test
  public void testSharedModuleNameTooLong() {
    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget = BuildTargetFactory.newInstance("//:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:test-shared-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(sharedLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestSharedLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget javaLibraryTarget =
        BuildTargetFactory.newInstance(
            "//:test-java-library-test.java.library.1=========10=========20=========30=========40=========50=========60=========70=========80=========90=========100========110========120========130========140========150========160========170========180========190========200========210========220========230========240========250===");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget, commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    Set<BuildTarget> seedTargets = new HashSet<>();
    seedTargets.add(androidLibraryTarget);
    seedTargets.add(javaLibraryTarget);

    APKModuleGraph dag = new APKModuleGraph(graph, androidBinaryTarget, Optional.of(seedTargets));

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(2));

    final List<String> expectedModuleNames = new ArrayList<>();
    expectedModuleNames.add("test.android.library");
    expectedModuleNames.add(
        "test.java.library.test.java.library.1=========10=========20=========30=========40=========50=========60=========70=========80=========90=========100========110========120========130========140========150========160========170========180========190========200========210========220========230========240========250===");
    Collections.sort(expectedModuleNames);
    final String sharedModuleName = "s_" + String.join("_", expectedModuleNames);
    for (APKModule apkModule : topLevelNodes) {
      assertThat(apkModule.getName(), isIn(expectedModuleNames));
      ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(apkModule);

      assertThat(dependencies.size(), is(2));
      assertThat(
          Iterables.getFirst(dependencies, null).getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
      assertThat(Iterables.getLast(dependencies, null).getName(), not(sharedModuleName));
      assertThat(Iterables.getLast(dependencies, null).getName().length(), lessThan(255));
      assertThat(Iterables.getLast(dependencies, null).getName(), startsWith("s_"));
    }
  }

  /*
                         + - - - - - -+
                       ' root:      '
                       '            '
                       ' +--------+ '
       +-------------- ' | Binary | ' --------+
       |               ' +--------+ '         |
       |               '   |        '         |
       |               '   |        '         |
       v               '   |        '         v
   + - - - - - - +     '   |        '     +- - - - - +
   ' android:    '     '   |        '     ' java:    '
   '             '     '   |        '     '          '
   ' +---------+ '     '   |        '     ' +------+ '
   ' | Android | '     '   |        '     ' | Java | '
   ' +---------+ '     '   |        '     ' +------+ '
   '             '     '   |        '     '          '
   + - - - - - - +     '   |        '     +- - - - - +
       |               '   |        '         |
       |               '   |        '         |
       |               '   v        '         |
       |               ' +--------+ '         |
       +-------------> ' | Common | ' <-------+
                       ' +--------+ '
                       '            '
                       + - - - - - -+
  */
  @Test
  public void testAPKModuleGraphSimple() {
    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget =
        BuildTargetFactory.newInstance(
            "//src/com/facebook/test-common-library:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget javaLibraryTarget =
        BuildTargetFactory.newInstance("//src/com/facebook/test-java-library:test-java-library")
            .withFlavors(InternalFlavor.of("flavor"));
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget androidLibraryTarget =
        BuildTargetFactory.newInstance(
            "//src/com/facebook/test-android-library:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget =
        BuildTargetFactory.newInstance(
            "//src/com/facebook/test-android-binary:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget, commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    Set<BuildTarget> seedTargets = new HashSet<>();
    seedTargets.add(androidLibraryTarget);
    seedTargets.add(javaLibraryTarget);

    APKModuleGraph dag = new APKModuleGraph(graph, androidBinaryTarget, Optional.of(seedTargets));

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(2));

    for (APKModule apkModule : topLevelNodes) {
      assertThat(
          apkModule.getName(),
          oneOf(
              "src.com.facebook.test.android.library",
              "src.com.facebook.test.java.library.test.java.library.flavor"));
      ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(apkModule);
      assertThat(apkModule.isRootModule(), is(false));

      assertThat(dependencies.size(), is(1));
      assertThat(
          Iterables.getFirst(dependencies, null).getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
      assertThat(Iterables.getFirst(dependencies, null).isRootModule(), is(true));
    }

    assertThat(
        dag.getAPKModules().stream().map(APKModule::getName).collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(
            "dex",
            "src.com.facebook.test.android.library",
            "src.com.facebook.test.java.library.test.java.library.flavor"));
  }

  /*
                           +- - - - - - - - - - - - +
                           ' root:                  '
                           '                        '
                           ' +--------------------+ '
      +------------------- ' |       Binary       | ' -------------+
      |                    ' +--------------------+ '              |
      |                    '   |                    '              |
      |                    '   |                    '              |
      v                    '   |                    '              v
  + - - - - - - +          '   |                    '          +- - - - - +
  ' android:    '          '   |                    '          ' java:    '
  '             '          '   v                    '          '          '
  ' +---------+ '          ' +--------------------+ '          ' +------+ '
  ' | Android | ' =======> ' |       Common       | ' <======= ' | Java | '
  ' +---------+ '          ' +--------------------+ '          ' +------+ '
  '             '          '                        '          '          '
  + - - - - - - +          +- - - - - - - - - - - - +          +- - - - - +
      H                                                            H
      H                                                            H
      H                                                            H
      H                    +- - - - - - - - - - - - +              H
      H                    ' shared_android_java:   '              H
      H                    '                        '              H
      H                    ' +--------------------+ '              H
      #==================> ' |       Shared       | ' <============#
                           ' +--------------------+ '
                           '                        '
                           +- - - - - - - - - - - - +

     -- target dependecy
     == package and target dependecy
     */
  @Test
  public void testAPKModuleGraphSharedDependency() {

    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget = BuildTargetFactory.newInstance("//:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:test-shared-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(sharedLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestSharedLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget, commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    Set<BuildTarget> seedTargets = new HashSet<>();
    seedTargets.add(androidLibraryTarget);
    seedTargets.add(javaLibraryTarget);

    APKModuleGraph dag = new APKModuleGraph(graph, androidBinaryTarget, Optional.of(seedTargets));

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(2));

    final List<String> expectedModuleNames = new ArrayList<>();
    expectedModuleNames.add("test.android.library");
    expectedModuleNames.add("test.java.library");
    Collections.sort(expectedModuleNames);
    final String sharedModuleName = "s_" + String.join("_", expectedModuleNames);
    for (APKModule apkModule : topLevelNodes) {
      assertThat(apkModule.getName(), isIn(expectedModuleNames));
      ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(apkModule);

      assertThat(dependencies.size(), is(2));
      assertThat(
          Iterables.getFirst(dependencies, null).getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
      assertThat(Iterables.getLast(dependencies, null).getName(), is(sharedModuleName));
    }
  }

  /*
                             +- - - - - - - - - - - - +
                             ' root:                  '
                             '                        '
                             ' +--------------------+ '
        +------------------- ' |       Binary       | ' ---------------------+
        |                    ' +--------------------+ '                      |
        |                    '   |                    '                      |
        |                    '   |                    '                      |
        v                    '   |                    '                      v
    + - - - - - - +          '   |                    '          +- - - - - - - - - - - - +
    ' android:    '          '   |                    '          ' java:                  '
    '             '          '   v                    '          '                        '
    ' +---------+ '          ' +--------------------+ '          ' +--------------------+ '
    ' | Android | ' =======> ' |       Common       | ' <======= ' |        Java        | '
    ' +---------+ '          ' +--------------------+ '          ' +--------------------+ '
    '             '          '                        '          '           |            '
    + - - - - - - +          +- - - - - - - - - - - - +          '           |            '
        H                                                        '           v            '
        H                                                        ' +--------------------+ '
        #======================================================> ' |       Shared       | '
                                                                 ' +--------------------+ '
                                                                 '                        '
                                                                 +- - - - - - - - - - - - +

       Android module depends on the Java Module
       -- target dependecy
       == package and target dependecy
  */
  @Test
  public void testAPKModuleGraphWithDeclaredDependency() {
    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget = BuildTargetFactory.newInstance("//:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:test-shared-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(sharedLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestSharedLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget, commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    ImmutableMap.Builder<String, ImmutableList<BuildTarget>> seedConfigMap = ImmutableMap.builder();

    seedConfigMap.put("android", ImmutableList.of(androidLibraryTarget));

    seedConfigMap.put("java", ImmutableList.of(javaLibraryTarget));

    ImmutableMap.Builder<String, ImmutableList<String>> appModuleDependencies =
        ImmutableMap.builder();
    appModuleDependencies.put("android", ImmutableList.of("java"));

    APKModuleGraph dag =
        new APKModuleGraph(
            Optional.of(seedConfigMap.build()),
            Optional.of(appModuleDependencies.build()),
            Optional.empty(),
            ImmutableSet.of(),
            graph,
            androidBinaryTarget);

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(1));

    APKModule topModule = Iterables.getFirst(topLevelNodes, null);
    assertThat(topModule.getName(), is("android"));

    ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(topModule);
    assertThat(dependencies.size(), is(2));

    for (APKModule dependency : dependencies) {
      assertThat(dependency.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java"));
      if (dependency.getName().equals("java")) {
        ImmutableSet<APKModule> javaDeps = dag.getGraph().getOutgoingNodesFor(dependency);
        assertThat(javaDeps.size(), is(1));
        assertThat(
            Iterables.getFirst(javaDeps, null).getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
      }
    }
  }

  /*
                        +- - - - - - - - - - - - +
                        ' root:                  '
                        '                        '
                        ' +--------------------+ ' -----------------------+
        +-------------- ' |       Binary       | ' --------+              |
        |               ' +--------------------+ '         |              |
        |               '   |                    '         |              |
        |               '   |                    '         |              |
        v               '   |                    '         v              v
    + - - - - - - +     '   |                    '    + - - - - - +   + - - - - - +
    ' android:    '     '   |                    '    ' java:     '   ' java2:    '
    '             '     '   v                    '    '           '   '           '
    ' +---------+ '     ' +--------------------+ '    ' +-------+ '   ' +-------+ '
    ' | Android | ' ==> ' |       Common       | ' <= ' | Java  | '   ' | Java2 | '
    ' +---------+ '     ' +--------------------+ '    ' +-------+ '   ' +-------+ '
    '             '     '                        '    +- - - - - -+   + - - - - - +
    + - - - - - - +     +- - - - - - - - - - - - +           H            H  H
        |                            ^                       H            H  H
        |                            H                       H            H  H
        |                            #=======================#============#  H
        |                                                    H               H
        |                                                    v               H
        |                                             + - - - - - +          H
        |                                             ' shared:   '          H
        |                                             '           '          H
        |                                             ' +-------+ '          H
        +-------------------------------------------> ' |Shared | ' <========#
                                                      ' +-------+ '
                                                      + - - - - - +

       Android module depends on the Java module.
       -- target dependecy
       == package and target dependecy
  */

  @Test
  public void testAPKModuleGraphSharedWithDeclaredDependency() {

    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget = BuildTargetFactory.newInstance("//:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:test-shared-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(sharedLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestSharedLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget java2LibraryTarget = BuildTargetFactory.newInstance("//:test-java2-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(java2LibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJava2Library.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(
                    androidLibraryTarget,
                    javaLibraryTarget,
                    java2LibraryTarget,
                    commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    ImmutableMap.Builder<String, ImmutableList<BuildTarget>> seedConfigMap = ImmutableMap.builder();

    seedConfigMap.put("android", ImmutableList.of(androidLibraryTarget));

    seedConfigMap.put("java", ImmutableList.of(javaLibraryTarget));

    seedConfigMap.put("java2", ImmutableList.of(java2LibraryTarget));

    ImmutableMap.Builder<String, ImmutableList<String>> appModuleDependencies =
        ImmutableMap.builder();
    appModuleDependencies.put("android", ImmutableList.of("java"));

    APKModuleGraph dag =
        new APKModuleGraph(
            Optional.of(seedConfigMap.build()),
            Optional.of(appModuleDependencies.build()),
            Optional.empty(),
            ImmutableSet.of(),
            graph,
            androidBinaryTarget);

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(2));

    for (APKModule topModule : topLevelNodes) {
      assertThat(topModule.getName(), oneOf("java2", "android"));
      switch (topModule.getName()) {
        case "java2":
          Set<APKModule> java2Dependencies = dag.getGraph().getOutgoingNodesFor(topModule);
          for (APKModule dependency : java2Dependencies) {
            assertThat(
                dependency.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "s_java_java2"));
          }
          break;
        case "android":
          Set<APKModule> androidDependencies = dag.getGraph().getOutgoingNodesFor(topModule);
          for (APKModule dependency : androidDependencies) {
            assertThat(dependency.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java"));
            if (dependency.getName().equals("java")) {
              Set<APKModule> javaDeps = dag.getGraph().getOutgoingNodesFor(dependency);
              for (APKModule javaDep : javaDeps) {
                assertThat(
                    javaDep.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "s_java_java2"));
              }
            }
          }
          break;
        default:
      }
    }
  }

  /*
                             +- - - - - - - - - - - - +
                             ' root:                  '
                             '                        '
                             ' +--------------------+ '
        +------------------- ' |       Binary       | ' ---------------------+
        |                    ' +--------------------+ '                      |
        |                                                                    |
        |                                                                    |
        v                                                                    v
    + - - - - - - +                                              +- - - - - - - - - - - - +
    ' android:    '                                              ' java:                  '
    '             '                                              '                        '
    ' +---------+ '                                              ' +--------------------+ '
    ' | Android | ' -------------------------------------------> ' |        Java        | '
    ' +---------+ '                                              ' +--------------------+ '
    '             '                                              '                        '
    + - - - - - - +                                              +- - - - - - - - - - - - +


       Android module depends on the Java Module
       -- target dependecy
       == package and target dependecy
  */
  @Test
  public void testAPKModuleGraphWithMissingDeclaredDependency() {
    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(javaLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    ImmutableMap.Builder<String, ImmutableList<BuildTarget>> seedConfigMap = ImmutableMap.builder();
    seedConfigMap.put("android", ImmutableList.of(androidLibraryTarget));
    seedConfigMap.put("java", ImmutableList.of(javaLibraryTarget));

    APKModuleGraph dag =
        new APKModuleGraph(
            Optional.of(seedConfigMap.build()),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            graph,
            androidBinaryTarget);

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(1));

    APKModule topModule = Iterables.getFirst(topLevelNodes, null);
    assertThat(topModule.getName(), is("android"));

    ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(topModule);
    assertThat(dependencies.size(), is(2));

    for (APKModule dependency : dependencies) {
      assertThat(dependency.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java"));
      if (dependency.getName().equals("java")) {
        ImmutableSet<APKModule> javaDeps = dag.getGraph().getOutgoingNodesFor(dependency);
        assertThat(javaDeps.size(), is(1));
        assertThat(
            Iterables.getFirst(javaDeps, null).getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
      }
    }
  }

  /*
                             +- - - - - - - - - - - - +
                             ' root:                  '
                             '                        '
                             ' +--------------------+ '
        +------------------- ' |       Binary       | ' ---------------------+
        |                    ' +--------------------+ '                      |
        |                                                                    |
        |                                                                    |
        v                                                                    v
    + - - - - - - +                                              +- - - - - - - - - - - - +
    ' android:    '                                              ' java:                  '
    '             '                                              '                        '
    ' +---------+ '                                              ' +--------------------+ '
    ' | Android | ' ===========================================> ' |        Java        | '
    ' +---------+ '                                              ' +--------------------+ '
    '             '                                              '          |             '
    + - - - - - - +                                              '          |             '
                                                                 '          |             '
                                                                 '          v             '
                                                                 ' +--------------------+ '
                                                                 ' |    Java Dep        | '
                                                                 ' +--------------------+ '
                                                                 +- - - - - - - - - - - - +


       Android module depends on the Java Module
       -- target dependecy
       == package and target dependecy
  */
  @Test
  public void testAPKModuleGraphWithUndeclaredTransitiveDeps() {
    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget javaDepLibraryTarget = BuildTargetFactory.newInstance("//:test-java-dep-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(javaDepLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaDepLibrary.java"))
            .build());

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(javaDepLibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(javaLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    ImmutableMap.Builder<String, ImmutableList<BuildTarget>> seedConfigMap = ImmutableMap.builder();
    seedConfigMap.put("android", ImmutableList.of(androidLibraryTarget));
    seedConfigMap.put("java", ImmutableList.of(javaLibraryTarget));
    seedConfigMap.put("java-dep", ImmutableList.of(javaDepLibraryTarget));

    APKModuleGraph dag =
        new APKModuleGraph(
            Optional.of(seedConfigMap.build()),
            Optional.empty(),
            Optional.empty(),
            ImmutableSet.of(),
            graph,
            androidBinaryTarget);

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(1));

    APKModule topModule = Iterables.getFirst(topLevelNodes, null);
    assertThat(topModule.getName(), is("android"));

    ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(topModule);
    assertThat(dependencies.size(), is(2));

    for (final APKModule dependency : dependencies) {
      assertThat(
          dependency.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java", "java-dep"));
      if (dependency.getName().equals("java")) {
        ImmutableSet<APKModule> javaDeps = dag.getGraph().getOutgoingNodesFor(dependency);
        assertThat(javaDeps.size(), is(2));
        for (final APKModule javaDep : javaDeps) {
          assertThat(javaDep.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java-dep"));
        }
      } else if (dependency.getName().equals("java-dep")) {
        ImmutableSet<APKModule> javaDeps = dag.getGraph().getOutgoingNodesFor(dependency);
        assertThat(javaDeps.size(), is(1));
        assertThat(
            Iterables.getFirst(javaDeps, null).getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
      }
    }
  }

  /*

                                                             + - - - - - - - - - +
                                                             ' root:             '
                                                             '                   '
              +--------------------------------------------- ' +---------------+ '
              |             +------------------------------- ' |    Binary     | '
              v             |              +---------------- ' +---------------+ '
     + - - - - - - +        |              |                 '         |         '
     ' android:    '        |              |                 '         |         '
     '             '        |              |                 '         v         '
     ' +---------+ ' =======+==============+===#             ' +---------------+ '
     ' | Android | '        v              |   H             ' |               | '
     ' +---------+ '   + - - - - - +       |   #===========> ' |               | '
     '             '   ' java:     '       |                 ' |               | '
     + - - - - - - +   '           '       |                 ' |               | '
           |   H       ' +-------+ ' ======+===============> ' |               | '
           |   #=====> ' | Java  | '       v                 ' |    Common     | '
           |           ' +-------+ '   + - - - - - - - +     ' |               | '
           |           '           '   ' java2:        '     ' |               | '
           |           + - - - - - +   '               '     ' |               | '
           |               |   H       ' +-----------+ '     ' |               | '
           |               |   #=====> ' |   Java2   | ' ==> ' |               | '
           |               |           ' +-----------+ '     ' +---------------+ '
           |               |           '               '     '                   '
           |               |           '               '     + - - - - - - - - - +
           |               +---------> ' +-----------+ '
           |                           ' |  Shared   | '
           +-------------------------> ' +-----------+ '
                                       '               '
                                       + - - - - - - - +

     There is a declared dependency from android -> java and java -> java2.
  */

  @Test
  public void testAPKModuleGraphWithMultiLevelDependencies() {

    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget = BuildTargetFactory.newInstance("//:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:test-shared-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(sharedLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestSharedLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget java2LibraryTarget = BuildTargetFactory.newInstance("//:test-java2-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(java2LibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJava2Library.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .addDep(java2LibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .addDep(java2LibraryTarget)
            .addDep(javaLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(
                    androidLibraryTarget,
                    javaLibraryTarget,
                    java2LibraryTarget,
                    commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    ImmutableMap.Builder<String, ImmutableList<BuildTarget>> seedConfigMap = ImmutableMap.builder();

    seedConfigMap.put("android", ImmutableList.of(androidLibraryTarget));
    seedConfigMap.put("java", ImmutableList.of(javaLibraryTarget));
    seedConfigMap.put("java2", ImmutableList.of(java2LibraryTarget));

    ImmutableMap.Builder<String, ImmutableList<String>> appModuleDependencies =
        ImmutableMap.builder();

    appModuleDependencies.put("android", ImmutableList.of("java", "java2"));
    appModuleDependencies.put("java", ImmutableList.of("java2"));

    APKModuleGraph dag =
        new APKModuleGraph(
            Optional.of(seedConfigMap.build()),
            Optional.of(appModuleDependencies.build()),
            Optional.empty(),
            ImmutableSet.of(),
            graph,
            androidBinaryTarget);

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(1));

    APKModule topModule = Iterables.getFirst(topLevelNodes, null);
    assertThat(topModule.getName(), is("android"));

    ImmutableSet<APKModule> topLevelDeps = dag.getGraph().getOutgoingNodesFor(topModule);
    assertThat(topLevelDeps.size(), is(3));

    APKModule middleModule = null;
    for (APKModule apkModule : topLevelDeps) {
      assertThat(apkModule.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java", "java2"));
      if (apkModule.getName().equals("java")) {
        middleModule = apkModule;
      }
    }

    ImmutableSet<APKModule> middleLevelDeps = dag.getGraph().getOutgoingNodesFor(middleModule);
    assertThat(middleLevelDeps.size(), is(2));

    APKModule bottomModule = null;
    for (APKModule apkModule : middleLevelDeps) {
      assertThat(apkModule.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java2"));
      if (apkModule.getName().equals("java2")) {
        bottomModule = apkModule;
      }
    }

    ImmutableSet<APKModule> bottomLevelDeps = dag.getGraph().getOutgoingNodesFor(bottomModule);
    assertThat(bottomLevelDeps.size(), is(1));

    APKModule bottomDep = Iterables.getFirst(bottomLevelDeps, null);
    assertThat(bottomDep.getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
  }

  /*

                                                             + - - - - - - - - - +
                                                             ' root:             '
                                                             '                   '
              +--------------------------------------------- ' +---------------+ '
              |             +------------------------------- ' |    Binary     | '
              v             |              +---------------- ' +---------------+ '
     + - - - - - - +        |              |                 '         |         '
     ' android:    '        |              |                 '         |         '
     '             '        |              |                 '         v         '
     ' +---------+ ' =======+==============+===#             ' +---------------+ '
     ' | Android | '        v              |   H             ' |               | '
     ' +---------+ '   + - - - - - +       |   #===========> ' |               | '
     '             '   ' java:     '       |                 ' |               | '
     + - - - - - - +   '           '       |                 ' |               | '
           |   H       ' +-------+ ' ======+===============> ' |               | '
           |   #=====> ' | Java  | '       v                 ' |    Common     | '
           |           ' +-------+ '   + - - - - - - - +     ' |               | '
           |           '           '   ' java2:        '     ' |               | '
           |           + - - - - - +   '               '     ' |               | '
           |                           ' +-----------+ '     ' |               | '
           |                           ' |   Java2   | ' ==> ' |               | '
           |                           ' +-----------+ '     ' +---------------+ '
           |                           '               '     '                   '
           |                           '               '     + - - - - - - - - - +
           |                           ' +-----------+ '
           |                           ' |  Shared   | '
           +-------------------------> ' +-----------+ '
                                       '               '
                                       + - - - - - - - +

     There is a declared dependency from android -> java and java -> java2.
  */

  @Test
  public void testAPKModuleGraphThatLowestDeclaredDepTakesCareOfMultipleLevelsOfIndirection() {

    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget = BuildTargetFactory.newInstance("//:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:test-shared-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(sharedLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestSharedLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget java2LibraryTarget = BuildTargetFactory.newInstance("//:test-java2-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(java2LibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJava2Library.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(java2LibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .addDep(java2LibraryTarget)
            .addDep(javaLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(
                    androidLibraryTarget,
                    javaLibraryTarget,
                    java2LibraryTarget,
                    commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    ImmutableMap.Builder<String, ImmutableList<BuildTarget>> seedConfigMap = ImmutableMap.builder();

    seedConfigMap.put("android", ImmutableList.of(androidLibraryTarget));
    seedConfigMap.put("java", ImmutableList.of(javaLibraryTarget));
    seedConfigMap.put("java2", ImmutableList.of(java2LibraryTarget));

    ImmutableMap.Builder<String, ImmutableList<String>> appModuleDependencies =
        ImmutableMap.builder();

    appModuleDependencies.put("android", ImmutableList.of("java", "java2"));
    appModuleDependencies.put("java", ImmutableList.of("java2"));

    APKModuleGraph dag =
        new APKModuleGraph(
            Optional.of(seedConfigMap.build()),
            Optional.of(appModuleDependencies.build()),
            Optional.empty(),
            ImmutableSet.of(),
            graph,
            androidBinaryTarget);

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(1));

    APKModule topModule = Iterables.getFirst(topLevelNodes, null);
    assertThat(topModule.getName(), is("android"));

    ImmutableSet<APKModule> topLevelDeps = dag.getGraph().getOutgoingNodesFor(topModule);
    assertThat(topLevelDeps.size(), is(3));

    APKModule middleModule = null;
    for (APKModule apkModule : topLevelDeps) {
      assertThat(apkModule.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java", "java2"));
      if (apkModule.getName().equals("java")) {
        middleModule = apkModule;
      }
    }

    ImmutableSet<APKModule> middleLevelDeps = dag.getGraph().getOutgoingNodesFor(middleModule);
    assertThat(middleLevelDeps.size(), is(2));

    APKModule bottomModule = null;
    for (APKModule apkModule : middleLevelDeps) {
      assertThat(apkModule.getName(), oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "java2"));
      if (apkModule.getName().equals("java2")) {
        bottomModule = apkModule;
      }
    }

    ImmutableSet<APKModule> bottomLevelDeps = dag.getGraph().getOutgoingNodesFor(bottomModule);
    assertThat(bottomLevelDeps.size(), is(1));

    APKModule bottomDep = Iterables.getFirst(bottomLevelDeps, null);
    assertThat(bottomDep.getName(), is(APKModuleGraph.ROOT_APKMODULE_NAME));
  }

  /*
                   +----------------------------+
                   |                            |
                   |                            |
    +--------------+---------------+            |
    |              |               v            v
  +--------+     +---------+     +------+     +--------+     +--------+
  | Binary | --> | Android | --> | Java | --> | Shared | --> |        |
  +--------+     +---------+     +------+     +--------+     |        |
    |              |               |                         |        |
    +--------------+---------------+-----------------------> | Common |
                   |               |                         |        |
                   |               |                         |        |
                   +---------------+-----------------------> |        |
                                   |                         +--------+
                                   |                           ^
                                   +---------------------------+
  */

  @Test
  public void testAPKModuleGraphComplexDependencyTree() {
    ImmutableSet.Builder<TargetNode<?>> nodeBuilder = ImmutableSet.builder();
    BuildTarget commonLibraryTarget = BuildTargetFactory.newInstance("//:test-common-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(commonLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestCommonLibrary.java"))
            .build());

    BuildTarget sharedLibraryTarget = BuildTargetFactory.newInstance("//:test-shared-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(sharedLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestSharedLibrary.java"))
            .addDep(commonLibraryTarget)
            .build());

    BuildTarget javaDepLibraryTarget = BuildTargetFactory.newInstance("//:test-java-dep-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(javaDepLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaDepLibrary.java"))
            .build());

    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    nodeBuilder.add(
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addDep(commonLibraryTarget)
            .addDep(javaDepLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    nodeBuilder.add(
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(javaLibraryTarget)
            .addDep(commonLibraryTarget)
            .addDep(sharedLibraryTarget)
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    nodeBuilder.add(
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build());

    BuildTarget androidBinaryTarget = BuildTargetFactory.newInstance("//:test-android-binary");
    nodeBuilder.add(
        AndroidBinaryBuilder.createBuilder(androidBinaryTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(
                ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget, commonLibraryTarget))
            .build());

    TargetGraph graph = TargetGraphFactory.newInstance(nodeBuilder.build());

    Set<BuildTarget> seedTargets = new HashSet<>();
    seedTargets.add(androidLibraryTarget);
    seedTargets.add(javaLibraryTarget);

    ImmutableMap.Builder<String, ImmutableList<BuildTarget>> seedConfigMap = ImmutableMap.builder();
    seedConfigMap.put("android", ImmutableList.of(androidLibraryTarget));
    seedConfigMap.put("java", ImmutableList.of(javaLibraryTarget));

    ImmutableMap.Builder<String, ImmutableList<String>> appModuleDependencies =
        ImmutableMap.builder();
    appModuleDependencies.put("android", ImmutableList.of("java"));

    APKModuleGraph dag =
        new APKModuleGraph(
            Optional.of(seedConfigMap.build()),
            Optional.of(appModuleDependencies.build()),
            Optional.empty(),
            ImmutableSet.of(),
            graph,
            androidBinaryTarget);

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(1));

    for (APKModule apkModule : topLevelNodes) {
      assertThat(apkModule.getName(), equalTo("android"));
      ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(apkModule);

      for (APKModule depModule : dependencies) {
        assertThat(depModule.getName(), oneOf("java", APKModuleGraph.ROOT_APKMODULE_NAME));
        switch (depModule.getName()) {
          case APKModuleGraph.ROOT_APKMODULE_NAME:
            assertThat(dag.getGraph().getOutgoingNodesFor(depModule).size(), is(0));
            break;
          case "java":
            verifyDependencies(dag, depModule, ImmutableSet.of(APKModuleGraph.ROOT_APKMODULE_NAME));
            break;
        }
      }
    }
  }
}

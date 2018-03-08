/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android.apkmodule;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Paths;
import java.util.HashSet;
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
    ImmutableSet.Builder<TargetNode<?, ?>> nodeBuilder = ImmutableSet.builder();
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

    ImmutableSet.Builder<TargetNode<?, ?>> nodeBuilder = ImmutableSet.builder();
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

    for (APKModule apkModule : topLevelNodes) {
      assertThat(apkModule.getName(), oneOf("test.android.library", "test.java.library"));
      ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(apkModule);

      assertThat(dependencies.size(), is(2));
      assertThat(
          Iterables.getFirst(dependencies, null).getName(),
          oneOf(APKModuleGraph.ROOT_APKMODULE_NAME, "test.shared.library"));
    }
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
    ImmutableSet.Builder<TargetNode<?, ?>> nodeBuilder = ImmutableSet.builder();
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

    APKModuleGraph dag = new APKModuleGraph(graph, androidBinaryTarget, Optional.of(seedTargets));

    ImmutableSet<APKModule> topLevelNodes = dag.getGraph().getNodesWithNoIncomingEdges();
    assertThat(topLevelNodes.size(), is(2));

    for (APKModule apkModule : topLevelNodes) {
      assertThat(apkModule.getName(), oneOf("test.android.library", "test.java.library"));
      ImmutableSet<APKModule> dependencies = dag.getGraph().getOutgoingNodesFor(apkModule);

      for (APKModule depModule : dependencies) {
        assertThat(
            depModule.getName(),
            oneOf("test.java.library", "test.shared.library", APKModuleGraph.ROOT_APKMODULE_NAME));
        switch (depModule.getName()) {
          case APKModuleGraph.ROOT_APKMODULE_NAME:
            assertThat(dag.getGraph().getOutgoingNodesFor(depModule).size(), is(0));
            break;
          case "test.java.library":
            verifyDependencies(
                dag,
                depModule,
                ImmutableSet.of("test.shared.library", APKModuleGraph.ROOT_APKMODULE_NAME));
            break;
          case "test.shared.library":
            verifyDependencies(dag, depModule, ImmutableSet.of(APKModuleGraph.ROOT_APKMODULE_NAME));
            break;
        }
      }
    }
  }
}

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

package com.facebook.buck.cli;

import static com.facebook.buck.jvm.java.JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TestTargetGraphCreationResultFactory;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.versions.VersionedAliasBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.SortedSet;
import org.junit.Before;
import org.junit.Test;

public class AuditClasspathCommandTest {

  private TestConsole console;
  private AuditClasspathCommand auditClasspathCommand;
  private CommandRunnerParams params;
  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    console = new TestConsole();
    auditClasspathCommand = new AuditClasspathCommand();
    params =
        CommandRunnerParamsForTesting.builder()
            .setConsole(console)
            .setToolchainProvider(AndroidBinaryBuilder.createToolchainProviderForAndroidBinary())
            .build();
    projectFilesystem = new FakeProjectFilesystem();
  }

  @Test
  public void testClassPathOutput() throws Exception {
    // Test that no output is created.
    auditClasspathCommand.printClasspath(
        params,
        TestTargetGraphCreationResultFactory.create(
            TargetGraphFactory.newInstance(ImmutableSet.of())));
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Add build rules such that all implementations of HasClasspathEntries are tested.
    BuildTarget javaLibraryTarget = BuildTargetFactory.newInstance("//:test-java-library");
    BuildTarget testJavaTarget = BuildTargetFactory.newInstance("//:project-tests");
    BuildTarget androidLibraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//:keystore");
    BuildTarget testAndroidTarget = BuildTargetFactory.newInstance("//:test-android-binary");

    TargetNode<?> javaLibraryNode =
        JavaLibraryBuilder.createBuilder(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .addTest(testJavaTarget)
            .build();
    TargetNode<?> androidLibraryNode =
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(javaLibraryTarget)
            .build();
    TargetNode<?> keystoreNode =
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of("debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore.properties"))
            .build();
    TargetNode<?> testAndroidNode =
        AndroidBinaryBuilder.createBuilder(testAndroidTarget)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(ImmutableSortedSet.of(androidLibraryTarget, javaLibraryTarget))
            .build();
    TargetNode<?> testJavaNode =
        JavaTestBuilder.createBuilder(testJavaTarget)
            .addDep(javaLibraryTarget)
            .addSrc(Paths.get("src/com/facebook/test/ProjectTests.java"))
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            ImmutableSet.of(
                javaLibraryNode, androidLibraryNode, keystoreNode, testAndroidNode, testJavaNode));
    auditClasspathCommand.printClasspath(
        params, TestTargetGraphCreationResultFactory.create(targetGraph));

    // Still empty.
    assertEquals("", console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Request the top build target. This will test the following:
    // - paths don't appear multiple times when dependencies are referenced multiple times.
    // - dependencies are walked
    // - independent targets in the same BUCK file are not included in the output
    auditClasspathCommand.printClasspath(
        params, TargetGraphCreationResult.of(targetGraph, ImmutableSet.of(testAndroidTarget)));

    AbsPath root = projectFilesystem.getRootPath();
    SortedSet<String> expectedPaths =
        Sets.newTreeSet(
            Arrays.asList(
                root.resolve(
                        BuildTargetPaths.getGenPath(
                                params.getCells().getRootCell().getFilesystem(),
                                androidLibraryTarget,
                                "lib__%s__output")
                            .resolve(androidLibraryTarget.getShortName() + ".jar"))
                    .toString(),
                root.resolve(
                        BuildTargetPaths.getGenPath(
                                params.getCells().getRootCell().getFilesystem(),
                                javaLibraryTarget,
                                "lib__%s__output")
                            .resolve(javaLibraryTarget.getShortName() + ".jar"))
                    .toString()));
    String expectedClasspath =
        String.join(System.lineSeparator(), expectedPaths) + System.lineSeparator();

    assertEquals(expectedClasspath, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());

    // Add independent test target. This will test:
    // - the union of the classpath is output.
    // - all rules have implemented HasClasspathEntries.
    // Note that the output streams are reset.
    setUp();
    auditClasspathCommand.printClasspath(
        params,
        TargetGraphCreationResult.of(
            TargetGraphFactory.newInstance(
                ImmutableSet.of(
                    javaLibraryNode,
                    androidLibraryNode,
                    keystoreNode,
                    testAndroidNode,
                    testJavaNode)),
            ImmutableSet.of(
                testAndroidTarget, javaLibraryTarget, androidLibraryTarget, testJavaTarget)));

    BuildTarget testJavaCompiledJar = testJavaTarget.withFlavors(COMPILED_TESTS_LIBRARY_FLAVOR);

    expectedPaths.add(
        root.resolve(
                BuildTargetPaths.getGenPath(
                        params.getCells().getRootCell().getFilesystem(),
                        testJavaCompiledJar,
                        "lib__%s__output")
                    .resolve(testJavaCompiledJar.getShortNameAndFlavorPostfix() + ".jar"))
            .toString());
    expectedClasspath = String.join(System.lineSeparator(), expectedPaths) + System.lineSeparator();
    assertEquals(expectedClasspath, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  private static final String EXPECTED_JSON =
      Joiner.on("")
          .join(
              "{",
              "\"//:test-android-library\":",
              "[",
              "%s,",
              "%s",
              "],",
              "\"//:test-java-library\":",
              "[",
              "%s",
              "]",
              "}");

  @Test
  public void testJsonClassPathOutput() throws Exception {
    // Build a DependencyGraph of build rules manually.

    BuildTarget javaTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> javaNode =
        JavaLibraryBuilder.createBuilder(javaTarget)
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .build();

    BuildTarget androidTarget = BuildTargetFactory.newInstance("//:test-android-library");
    TargetNode<?> androidNode =
        AndroidLibraryBuilder.createBuilder(androidTarget)
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(javaTarget)
            .build();

    auditClasspathCommand.printJsonClasspath(
        params,
        TargetGraphCreationResult.of(
            TargetGraphFactory.newInstance(ImmutableSet.of(androidNode, javaNode)),
            ImmutableSet.of(androidTarget, javaTarget)));

    AbsPath root = projectFilesystem.getRootPath();
    ObjectMapper objectMapper = ObjectMappers.legacyCreate();
    String expected =
        String.format(
            EXPECTED_JSON,
            objectMapper.valueToTree(
                root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            javaTarget,
                            "lib__%s__output")
                        .resolve(javaTarget.getShortName() + ".jar"))),
            objectMapper.valueToTree(
                root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            androidTarget,
                            "lib__%s__output")
                        .resolve(androidTarget.getShortName() + ".jar"))),
            objectMapper.valueToTree(
                root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            javaTarget,
                            "lib__%s__output")
                        .resolve(javaTarget.getShortName() + ".jar"))));
    assertEquals(expected, console.getTextWrittenToStdOut());

    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void testClassPathWithVersions() throws Exception {

    // Build the test target graph.
    TargetNode<?> javaLibrary =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:test-java-library"))
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .build();
    TargetNode<?> androidLibrary =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:test-android-library"))
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(javaLibrary.getBuildTarget())
            .build();
    TargetNode<?> version =
        new VersionedAliasBuilder(BuildTargetFactory.newInstance("//:version"))
            .setVersions("1.0", "//:test-android-library")
            .build();
    TargetNode<?> binary =
        new JavaBinaryRuleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setDeps(ImmutableSortedSet.of(version.getBuildTarget()))
            .build();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(javaLibrary, androidLibrary, version, binary);

    // Run the command.
    ImmutableSet<BuildTarget> targets =
        ImmutableSet.of(androidLibrary.getBuildTarget(), javaLibrary.getBuildTarget());
    auditClasspathCommand.printClasspath(
        params.withBuckConfig(
            FakeBuckConfig.builder()
                .setSections(ImmutableMap.of("build", ImmutableMap.of("versions", "true")))
                .build()),
        TargetGraphCreationResult.of(targetGraph, targets));

    // Verify output.
    AbsPath root = projectFilesystem.getRootPath();
    ImmutableSortedSet<String> expectedPaths =
        ImmutableSortedSet.of(
            root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            androidLibrary.getBuildTarget(),
                            "lib__%s__output")
                        .resolve(androidLibrary.getBuildTarget().getShortName() + ".jar"))
                .toString(),
            root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            javaLibrary.getBuildTarget(),
                            "lib__%s__output")
                        .resolve(javaLibrary.getBuildTarget().getShortName() + ".jar"))
                .toString());
    String expectedClasspath =
        String.join(System.lineSeparator(), expectedPaths) + System.lineSeparator();
    assertEquals(expectedClasspath, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void testJsonClassPathWithVersions() throws Exception {

    // Build the test target graph.
    TargetNode<?> javaLibrary =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:test-java-library"))
            .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
            .build();
    TargetNode<?> androidLibrary =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:test-android-library"))
            .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
            .addDep(javaLibrary.getBuildTarget())
            .build();
    TargetNode<?> version =
        new VersionedAliasBuilder(BuildTargetFactory.newInstance("//:version"))
            .setVersions("1.0", "//:test-android-library")
            .build();
    TargetNode<?> binary =
        new JavaBinaryRuleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setDeps(ImmutableSortedSet.of(version.getBuildTarget()))
            .build();
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(javaLibrary, androidLibrary, version, binary);

    // Run the command.
    ImmutableSet<BuildTarget> targets =
        ImmutableSet.of(androidLibrary.getBuildTarget(), javaLibrary.getBuildTarget());
    auditClasspathCommand.printJsonClasspath(
        params.withBuckConfig(
            FakeBuckConfig.builder()
                .setSections(ImmutableMap.of("build", ImmutableMap.of("versions", "true")))
                .build()),
        TargetGraphCreationResult.of(targetGraph, targets));

    // Verify output.
    AbsPath root = projectFilesystem.getRootPath();
    ObjectMapper objectMapper = ObjectMappers.legacyCreate();
    String expected =
        String.format(
            EXPECTED_JSON,
            objectMapper.valueToTree(
                root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            javaLibrary.getBuildTarget(),
                            "lib__%s__output")
                        .resolve(javaLibrary.getBuildTarget().getShortName() + ".jar"))),
            objectMapper.valueToTree(
                root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            androidLibrary.getBuildTarget(),
                            "lib__%s__output")
                        .resolve(androidLibrary.getBuildTarget().getShortName() + ".jar"))),
            objectMapper.valueToTree(
                root.resolve(
                    BuildTargetPaths.getGenPath(
                            params.getCells().getRootCell().getFilesystem(),
                            javaLibrary.getBuildTarget(),
                            "lib__%s__output")
                        .resolve(javaLibrary.getBuildTarget().getShortName() + ".jar"))));
    assertEquals(expected, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }
}

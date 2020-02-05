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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidBinaryDescriptionArg;
import com.facebook.buck.android.AndroidBuildConfigBuilder;
import com.facebook.buck.android.AndroidBuildConfigDescriptionArg;
import com.facebook.buck.android.AndroidManifest;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidManifestDescriptionArg;
import com.facebook.buck.android.AndroidManifestFactory;
import com.facebook.buck.android.AndroidResourceBuilder;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.features.filegroup.FileGroupDescriptionArg;
import com.facebook.buck.features.filegroup.FilegroupBuilder;
import com.facebook.buck.features.zip.rules.Zip;
import com.facebook.buck.features.zip.rules.ZipFileDescription;
import com.facebook.buck.features.zip.rules.ZipFileDescriptionArg;
import com.facebook.buck.file.RemoteFileBuilder;
import com.facebook.buck.file.RemoteFileDescriptionArg;
import com.facebook.buck.file.downloader.impl.ExplodingDownloader;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JarGenrule;
import com.facebook.buck.jvm.java.JarGenruleDescription;
import com.facebook.buck.jvm.java.JarGenruleDescriptionArg;
import com.facebook.buck.jvm.java.JavaBinaryDescriptionArg;
import com.facebook.buck.jvm.java.JavaBinaryRuleBuilder;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.jvm.java.JavaTestBuilder;
import com.facebook.buck.jvm.java.JavaTestDescriptionArg;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.KeystoreDescriptionArg;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarDescriptionArg;
import com.facebook.buck.sandbox.NoSandboxExecutionStrategy;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.shell.GenruleDescriptionArg;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class IjProjectSourcePathResolverTest {

  private FakeProjectFilesystem filesystem;

  @Before
  public void setUp() throws Exception {
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void testGenrule() {
    BuildTarget target = BuildTargetFactory.newInstance("//lib:rule");
    TargetNode<GenruleDescriptionArg> node =
        GenruleBuilder.newGenruleBuilder(target)
            .setOut("i_am_an_output")
            .setCmd("echo hi > $OUT")
            .build(filesystem);
    assertOutputPathsEqual(node);
  }

  @Test
  public void testJarGenrule() {
    BuildTarget target = BuildTargetFactory.newInstance("//lib:jar_rule");
    AbstractNodeBuilder<
            JarGenruleDescriptionArg.Builder,
            JarGenruleDescriptionArg,
            JarGenruleDescription,
            JarGenrule>
        builder =
            new AbstractNodeBuilder<
                JarGenruleDescriptionArg.Builder,
                JarGenruleDescriptionArg,
                JarGenruleDescription,
                JarGenrule>(
                new JarGenruleDescription(
                    JavaLibraryBuilder.createToolchainProviderForJavaLibrary(),
                    FakeBuckConfig.builder().build(),
                    new NoSandboxExecutionStrategy()),
                target) {};
    TargetNode<JarGenruleDescriptionArg> node = builder.build(filesystem);
    assertOutputPathsEqual(node);
  }

  @Test
  public void testJavaBinary() {
    TargetNode<JavaBinaryDescriptionArg> binary =
        new JavaBinaryRuleBuilder(BuildTargetFactory.newInstance("//java:binary"))
            .build(filesystem);
    assertOutputPathsEqual(binary);
  }

  @Test
  public void testAndroidBinary() {
    BuildTarget target = BuildTargetFactory.newInstance("//app:app");
    BuildTarget keystore = BuildTargetFactory.newInstance("//app:keystore");
    TargetNode<KeystoreDescriptionArg> keystoreNode =
        KeystoreBuilder.createBuilder(keystore)
            .setStore(FakeSourcePath.of("keystore/debug.keystore"))
            .setProperties(FakeSourcePath.of("keystore/debug.keystore.properties"))
            .build(filesystem);
    TargetNode<AndroidBinaryDescriptionArg> node =
        AndroidBinaryBuilder.createBuilder(target)
            .setKeystore(keystore)
            .setManifest(FakeSourcePath.of("AndroidManifest.xml"))
            .build(filesystem);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(keystoreNode, node);
    assertOutputPathsEqual(targetGraph, target);
  }

  @Test
  public void testAndroidResource() {
    TargetNode<AndroidResourceDescriptionArg> standardRes =
        AndroidResourceBuilder.createBuilder(BuildTargetFactory.newInstance("//android:res"))
            .setManifest(PathSourcePath.of(filesystem, Paths.get("AndroidManifest.xml")))
            .setRes(Paths.get("res"))
            .build(filesystem);
    assertOutputPathsEqual(standardRes);

    TargetNode<AndroidResourceDescriptionArg> withSymlinkTreeFlavor =
        AndroidResourceBuilder.createBuilder(
                BuildTargetFactory.newInstance("//android:res")
                    .withFlavors(AndroidResourceDescription.RESOURCES_SYMLINK_TREE_FLAVOR))
            .setManifest(PathSourcePath.of(filesystem, Paths.get("AndroidManifest.xml")))
            .setRes(Paths.get("res"))
            .build(filesystem);
    assertOutputPathsEqual(withSymlinkTreeFlavor);
  }

  @Test
  public void testAndroidResourceWithGenruleAsRes() {
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//android:genrule_for_res");
    TargetNode<GenruleDescriptionArg> genrule =
        GenruleBuilder.newGenruleBuilder(genruleTarget).setOut("res").build(filesystem);
    BuildTarget resTarget = BuildTargetFactory.newInstance("//android:res");
    TargetNode<AndroidResourceDescriptionArg> usesOutputOfOtherRule =
        AndroidResourceBuilder.createBuilder(resTarget)
            .setManifest(PathSourcePath.of(filesystem, Paths.get("AndroidManifest.xml")))
            .setRes(DefaultBuildTargetSourcePath.of(genruleTarget))
            .build(filesystem);

    assertOutputPathsEqual(
        TargetGraphFactory.newInstance(genrule, usesOutputOfOtherRule), resTarget);
  }

  @Test
  public void testPrebuiltJar() {
    TargetNode<PrebuiltJarDescriptionArg> node =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//prebuilt:one.jar"))
            .setBinaryJar(Paths.get("one.jar"))
            .build(filesystem);
    assertOutputPathsEqual(node);

    TargetNode<PrebuiltJarDescriptionArg> notAJar =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//prebuilt:other.foojar"))
            .setBinaryJar(Paths.get("one.foo"))
            .build(filesystem);
    assertOutputPathsEqual(notAJar);
  }

  @Test
  public void testJavaLibrary() {
    BuildTarget target = BuildTargetFactory.newInstance("//java:lib");
    TargetNode<JavaLibraryDescriptionArg> normal =
        JavaLibraryBuilder.createBuilder(target).addSrc(Paths.get("Foo.java")).build(filesystem);
    assertOutputPathsEqual(normal);
  }

  @Test
  public void testJavaTest() {
    BuildTarget target = BuildTargetFactory.newInstance("//test:junit");
    TargetNode<JavaTestDescriptionArg> node =
        JavaTestBuilder.createBuilder(target).addSrc(Paths.get("Test.java")).build(filesystem);
    assertOutputPathsEqual(node);
  }

  @Test
  public void testExportFile() {
    TargetNode<ExportFileDescriptionArg> byReference =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//files:export"))
            .setSrc(FakeSourcePath.of("source.txt"))
            .setMode(ExportFileDescription.Mode.REFERENCE)
            .build(filesystem);
    assertOutputPathsEqual(byReference);

    TargetNode<ExportFileDescriptionArg> byCopy =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//files:export"))
            .setSrc(FakeSourcePath.of("source.txt"))
            .setOut("out.txt")
            .setMode(ExportFileDescription.Mode.COPY)
            .build(filesystem);
    assertOutputPathsEqual(byCopy);
  }

  @Test
  public void testRemoteFile() {
    TargetNode<RemoteFileDescriptionArg> node =
        RemoteFileBuilder.createBuilder(
                new ExplodingDownloader(), BuildTargetFactory.newInstance("//files:remote"))
            .setSha1(HashCode.fromInt(0))
            .setUrl("https://example.com/remote_file")
            .build(filesystem);
    assertOutputPathsEqual(node);
  }

  @Test
  public void testFilegroup() {
    TargetNode<FileGroupDescriptionArg> node =
        FilegroupBuilder.createBuilder(BuildTargetFactory.newInstance("//files:group"))
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("file.txt")))
            .build(filesystem);
    assertOutputPathsEqual(node);
  }

  @Test
  public void testAndroidBuildConfig() {
    TargetNode<AndroidBuildConfigDescriptionArg> node =
        new AndroidBuildConfigBuilder(BuildTargetFactory.newInstance("//android:build_config"))
            .setPackage("com.example.foo")
            .build(filesystem);
    assertOutputPathsEqual(node);
  }

  @Test
  public void testAndroidManifest() {
    AbstractNodeBuilder<
            AndroidManifestDescriptionArg.Builder,
            AndroidManifestDescriptionArg,
            AndroidManifestDescription,
            AndroidManifest>
        builder =
            new AbstractNodeBuilder<
                AndroidManifestDescriptionArg.Builder,
                AndroidManifestDescriptionArg,
                AndroidManifestDescription,
                AndroidManifest>(
                new AndroidManifestDescription(new AndroidManifestFactory()),
                BuildTargetFactory.newInstance("//app:manifest")) {};
    builder
        .getArgForPopulating()
        .setSkeleton(PathSourcePath.of(filesystem, Paths.get("app/AndroidManifest.xml")));
    TargetNode<AndroidManifestDescriptionArg> node = builder.build(filesystem);
    assertOutputPathsEqual(node);
  }

  @Test
  public void testZipFile() {
    AbstractNodeBuilder<
            ZipFileDescriptionArg.Builder, ZipFileDescriptionArg, ZipFileDescription, Zip>
        builder =
            new AbstractNodeBuilder<
                ZipFileDescriptionArg.Builder, ZipFileDescriptionArg, ZipFileDescription, Zip>(
                new ZipFileDescription(), BuildTargetFactory.newInstance("//files:zip")) {};
    TargetNode<ZipFileDescriptionArg> node = builder.build(filesystem);
    assertOutputPathsEqual(node);
  }

  /**
   * Create the BuildRule for the given node and assert that the sourcePathToOutput matches what is
   * returned by the IjProjectSourcePathResolver based solely on the node definition.
   */
  private void assertOutputPathsEqual(TargetNode<?> targetNode) {
    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNode);
    assertOutputPathsEqual(targetGraph, targetNode.getBuildTarget());
  }

  /**
   * Assert that the given target in the graph calculates the same output when instantiated into a
   * real build rule as the output that we infer/guess in IjProjectSourcePathResolver
   */
  private void assertOutputPathsEqual(TargetGraph targetGraph, BuildTarget target) {
    TargetNode<?> node = targetGraph.get(target);
    DefaultBuildTargetSourcePath toResolve = DefaultBuildTargetSourcePath.of(node.getBuildTarget());

    // Calculate the real path
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePath sourcePathToOutput =
        graphBuilder.requireRule(node.getBuildTarget()).getSourcePathToOutput();
    Path realPath = graphBuilder.getSourcePathResolver().getRelativePath(sourcePathToOutput);

    // Find the guessed path
    SourcePathResolverAdapter projectSourcePathResolver =
        new SourcePathResolverAdapter(new IjProjectSourcePathResolver(targetGraph));
    Path guessedPath = projectSourcePathResolver.getRelativePath(toResolve);

    assertEquals(realPath, guessedPath);
  }
}

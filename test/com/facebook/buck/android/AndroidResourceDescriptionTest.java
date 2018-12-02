/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AndroidResourceDescriptionTest {

  @Rule public TemporaryFolder tmpFolder = new TemporaryFolder();

  @Test
  public void testNonAssetFilesAndDirsAreIgnored() throws IOException {
    tmpFolder.newFolder("res");

    tmpFolder.newFile("res/image.png");
    tmpFolder.newFile("res/layout.xml");
    tmpFolder.newFile("res/_file");

    tmpFolder.newFile("res/.gitkeep");
    tmpFolder.newFile("res/.svn");
    tmpFolder.newFile("res/.git");
    tmpFolder.newFile("res/.ds_store");
    tmpFolder.newFile("res/.scc");
    tmpFolder.newFile("res/CVS");
    tmpFolder.newFile("res/thumbs.db");
    tmpFolder.newFile("res/picasa.ini");
    tmpFolder.newFile("res/file.bak~");

    tmpFolder.newFolder("res", "dirs", "values");
    tmpFolder.newFile("res/dirs/values/strings.xml");
    tmpFolder.newFile("res/dirs/values/strings.xml.orig");

    tmpFolder.newFolder("res", "dirs", ".gitkeep");
    tmpFolder.newFile("res/dirs/.gitkeep/ignore");
    tmpFolder.newFolder("res", "dirs", ".svn");
    tmpFolder.newFile("res/dirs/.svn/ignore");
    tmpFolder.newFolder("res", "dirs", ".git");
    tmpFolder.newFile("res/dirs/.git/ignore");
    tmpFolder.newFolder("res", "dirs", ".ds_store");
    tmpFolder.newFile("res/dirs/.ds_store/ignore");
    tmpFolder.newFolder("res", "dirs", ".scc");
    tmpFolder.newFile("res/dirs/.scc/ignore");
    tmpFolder.newFolder("res", "dirs", "CVS");
    tmpFolder.newFile("res/dirs/CVS/ignore");
    tmpFolder.newFolder("res", "dirs", "thumbs.db");
    tmpFolder.newFile("res/dirs/thumbs.db/ignore");
    tmpFolder.newFolder("res", "dirs", "picasa.ini");
    tmpFolder.newFile("res/dirs/picasa.ini/ignore");
    tmpFolder.newFolder("res", "dirs", "file.bak~");
    tmpFolder.newFile("res/dirs/file.bak~/ignore");
    tmpFolder.newFolder("res", "dirs", "_dir");
    tmpFolder.newFile("res/dirs/_dir/ignore");

    AndroidResourceDescription description =
        new AndroidResourceDescription(
            new AndroidBuckConfig(FakeBuckConfig.builder().build(), Platform.detect()));
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot().toPath());
    Map<Path, SourcePath> inputs = description.collectInputFiles(filesystem, Paths.get("res"));

    assertThat(
        inputs,
        equalTo(
            ImmutableMap.<Path, SourcePath>of(
                Paths.get("image.png"),
                FakeSourcePath.of(filesystem, "res/image.png"),
                Paths.get("layout.xml"),
                FakeSourcePath.of(filesystem, "res/layout.xml"),
                Paths.get("_file"),
                FakeSourcePath.of(filesystem, "res/_file"),
                Paths.get("dirs/values/strings.xml"),
                FakeSourcePath.of(filesystem, "res/dirs/values/strings.xml"))));
  }

  @Test
  public void testPossibleResourceFileFiltering() {
    ImmutableList<Path> inputPaths =
        ImmutableList.of(
            Paths.get("res/image.png"),
            Paths.get("res/layout.xml"),
            Paths.get("res/_file"),
            Paths.get("res/.gitkeep"),
            Paths.get("res/.svn"),
            Paths.get("res/.git"),
            Paths.get("res/.ds_store"),
            Paths.get("res/.scc"),
            Paths.get("res/CVS"),
            Paths.get("res/thumbs.db"),
            Paths.get("res/picasa.ini"),
            Paths.get("res/file.bak~"),
            Paths.get("res/dirs/values/strings.xml"),
            Paths.get("res/dirs/values/strings.xml.orig"),
            Paths.get("res/dirs/.gitkeep/ignore"),
            Paths.get("res/dirs/.svn/ignore"),
            Paths.get("res/dirs/.ds_store/ignore"),
            Paths.get("res/dirs/.scc/ignore"),
            Paths.get("res/dirs/CVS/ignore"),
            Paths.get("res/dirs/thumbs.db/ignore"),
            Paths.get("res/dirs/picasa.ini/ignore"),
            Paths.get("res/dirs/file.bak~/ignore"),
            Paths.get("res/dirs/_dir/ignore"));

    ImmutableList<Path> expectedPaths =
        ImmutableList.of(
            Paths.get("res/image.png"),
            Paths.get("res/layout.xml"),
            Paths.get("res/_file"),
            Paths.get("res/dirs/values/strings.xml"));

    assertThat(
        RichStream.from(inputPaths)
            .filter(AndroidResourceDescription::isPossibleResourcePath)
            .toImmutableList(),
        is(equalTo(expectedPaths)));
  }

  @Test
  public void testResourceRulesCreateSymlinkTrees() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(tmpFolder.getRoot().toPath());
    filesystem.mkdirs(Paths.get("res"));
    filesystem.mkdirs(Paths.get("assets"));
    filesystem.createNewFile(Paths.get("res/file1.txt"));
    filesystem.createNewFile(Paths.get("res/thumbs.db"));
    filesystem.createNewFile(Paths.get("assets/file1.txt"));
    filesystem.createNewFile(Paths.get("assets/file2.txt"));
    filesystem.createNewFile(Paths.get("assets/file3.txt"));
    filesystem.createNewFile(Paths.get("assets/ignored"));
    filesystem.createNewFile(Paths.get("assets/picasa.ini"));

    BuildTarget target = BuildTargetFactory.newInstance("//:res");
    TargetNode<?> targetNode =
        AndroidResourceBuilder.createBuilder(target, filesystem)
            .setRDotJavaPackage("com.example")
            .setRes(FakeSourcePath.of(filesystem, "res"))
            .setAssets(
                ImmutableSortedMap.of(
                    "file1.txt", FakeSourcePath.of(filesystem, "assets/file1.txt"),
                    "file3.txt", FakeSourcePath.of(filesystem, "assets/file3.txt"),
                    "picasa.ini", FakeSourcePath.of(filesystem, "assets/ignored"),
                    "not_ignored", FakeSourcePath.of(filesystem, "assets/CVS")))
            .build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    AndroidResource resource = (AndroidResource) graphBuilder.requireRule(target);

    ImmutableList<BuildRule> deps = ImmutableList.copyOf(resource.getBuildDeps());
    assertThat(deps, hasSize(2));

    assertThat(
        deps.get(0).getBuildTarget(),
        is(
            equalTo(
                target.withAppendedFlavors(
                    AndroidResourceDescription.ASSETS_SYMLINK_TREE_FLAVOR))));
    assertThat(
        ((SymlinkTree) deps.get(0)).getLinks(),
        is(
            equalTo(
                ImmutableSortedMap.of(
                    Paths.get("file1.txt"),
                    FakeSourcePath.of(filesystem, "assets/file1.txt"),
                    Paths.get("file3.txt"),
                    FakeSourcePath.of(filesystem, "assets/file3.txt"),
                    Paths.get("not_ignored"),
                    FakeSourcePath.of(filesystem, "assets/CVS")))));

    assertThat(
        deps.get(1).getBuildTarget(),
        is(
            equalTo(
                target.withAppendedFlavors(
                    AndroidResourceDescription.RESOURCES_SYMLINK_TREE_FLAVOR))));
    assertThat(
        ((SymlinkTree) deps.get(1)).getLinks(),
        is(
            equalTo(
                ImmutableSortedMap.of(
                    Paths.get("file1.txt"), FakeSourcePath.of(filesystem, "res/file1.txt")))));
  }
}

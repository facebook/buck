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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Set;

public class AndroidResourceDescriptionTest {

  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder();

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

    AndroidResourceDescription description = new AndroidResourceDescription();
    ProjectFilesystem filesystem = new ProjectFilesystem(tmpFolder.getRoot().toPath());
    Set<SourcePath> inputs = description.collectInputFiles(
        filesystem,
            Optional.of(Paths.get("res")));

    assertThat(
        inputs,
        containsInAnyOrder(
            // This clever cast saves us mucking around with generics.
            (SourcePath) new FakeSourcePath(filesystem, "res/image.png"),
            new FakeSourcePath(filesystem, "res/layout.xml"),
            new FakeSourcePath(filesystem, "res/_file"),
            new FakeSourcePath(filesystem, "res/dirs/values/strings.xml")));
  }
}

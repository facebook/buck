/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.java.intellij;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IjModuleTest {

  @Test
  public void testFolderOverride() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/src/com/facebook/bar:foo");
    TargetNode<?> javaLibrary = JavaLibraryBuilder
        .createBuilder(buildTarget)
        .build();

    IjFolder inferredFolder = IjFolder.builder()
        .setPath(Paths.get("src"))
        .setType(IjFolder.Type.SOURCE_FOLDER)
        .setWantsPackagePrefix(true)
        .build();

    IjFolder overrideFolder = IjFolder.builder()
        .setPath(Paths.get("override"))
        .setType(IjFolder.Type.SOURCE_FOLDER)
        .setWantsPackagePrefix(false)
        .build();

    IjModule.Builder builder = IjModule.builder()
        .setTargets(ImmutableSet.<TargetNode<?>>of(javaLibrary))
        .setModuleBasePath(buildTarget.getBasePath())
        .addInferredFolders(inferredFolder);

    IjModule inferredModule = builder.build();
    IjModule overrideModule = builder.addFolderOverride(overrideFolder).build();

    assertEquals(ImmutableSet.of(inferredFolder), inferredModule.getFolders());
    assertEquals(ImmutableSet.of(overrideFolder), overrideModule.getFolders());
  }

  @Test
  public void testRelativeGenPath() {
    TargetNode<?> javaLibrary = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/foo:foo"))
        .build();

    IjModule javaLibraryModule = createModule(javaLibrary);

    assertEquals(Paths.get("../../../../../buck-out/android/java/src/com/facebook/foo/gen"),
        javaLibraryModule.getRelativeGenPath());
  }

  @Test
  public void testPathToModuleFile() {
    TargetNode<?> javaLibrary = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook/foo:foo"))
        .build();

    IjModule javaLibraryModule = createModule(javaLibrary);

    assertEquals(Paths.get("java", "src", "com", "facebook", "foo"),
        javaLibraryModule.getModuleBasePath());
    assertEquals("java_src_com_facebook_foo",
        javaLibraryModule.getModuleName());
    assertEquals(Paths.get("java", "src", "com", "facebook", "foo",
            "java_src_com_facebook_foo.iml"),
        javaLibraryModule.getModuleImlFilePath());
  }

  private static <T> IjModule createModule(TargetNode<?> targetNode) {
    Path moduleBasePath = targetNode.getBuildTarget().getBasePath();
    return IjModule.builder()
        .setTargets(ImmutableSet.<TargetNode<?>>of(targetNode))
        .setModuleBasePath(moduleBasePath)
        .build();
  }
}

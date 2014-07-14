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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.easymock.EasyMock;
import org.junit.Test;

import java.nio.file.Paths;

public class DefaultOnDiskBuildInfoTest {

  @Test
  public void testGetValues() {
    ProjectFilesystem projectFilesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(projectFilesystem
        .readFileIfItExists(Paths.get("buck-out/bin/foo/bar/.baz/metadata/KEY")))
        .andReturn(Optional.of("[\"bar\",\"biz\",\"baz\"]"));
    EasyMock.replay(projectFilesystem);

    BuildTarget buildTarget = BuildTarget.builder("//foo/bar", "baz").build();
    DefaultOnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(buildTarget,
        projectFilesystem);
    assertEquals(Optional.of(ImmutableList.of("bar", "biz", "baz")),
        onDiskBuildInfo.getValues("KEY"));

    EasyMock.verify(projectFilesystem);
  }
}

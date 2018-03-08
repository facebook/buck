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

package com.facebook.buck.util;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.packaged_resource.PackagedResourceTestUtil;
import org.junit.Test;

public class PackagedResourceIntegrationTest {
  @Test
  public void testPackagedResourceOnIndividualFile() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PackagedResource packagedResource =
        PackagedResourceTestUtil.getPackagedResource(filesystem, "testdata/packaged_resource_one");

    assertThat(packagedResource.getFilenamePath().toString(), is("packaged_resource_one"));

    assertThat(
        packagedResource.getResourceIdentifier(),
        is(
            "com.facebook.buck.testutil.packaged_resource.PackagedResourceTestUtil"
                + "#testdata/packaged_resource_one"));

    String fileContent = filesystem.readFileIfItExists(packagedResource.get()).get();
    assertThat(fileContent, is("abc"));
  }
}

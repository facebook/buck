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

package com.facebook.buck.android;

import static com.facebook.buck.testutil.MoreAsserts.assertIterablesEquals;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class GetStringsFilesStepTest {
  private ProjectFilesystem filesystem;
  private ExecutionContext context;

  private void setUpFakeFilesystem(Set<Path> files) {
    filesystem = new FakeProjectFilesystem(files);
    context = createMock(ExecutionContext.class);
    replay(context);
  }

  @Test
  public void testStringFileOrderIsMaintained() throws IOException {
    setUpFakeFilesystem(
        ImmutableSet.<Path>of(
            Paths.get("test/res/values/strings.xml"),
            Paths.get("test/res/values-es/strings.xml"),
            Paths.get("test/res/values-es-rES/strings.xml"),
            Paths.get("test2/res/values/strings.xml"),
            Paths.get("test2/res/values-es/strings.xml"),
            Paths.get("test2/res/values-es-rES/strings.xml"),
            Paths.get("test3/res/values/strings.xml"),
            Paths.get("test3/res/values-es/strings.xml"),
            Paths.get("test3/res/values-es-rES/strings.xml"),
            Paths.get("test3/res/values/dimens.xml")));

    ImmutableList.Builder<Path> stringFilesBuilder = ImmutableList.builder();
    GetStringsFilesStep step = new GetStringsFilesStep(
        filesystem,
        ImmutableList.of(Paths.get("test3"), Paths.get("test"), Paths.get("test2")),
        stringFilesBuilder,
        ImmutableSet.<Path>of());

    assertEquals(0, step.execute(context));

    ImmutableList<Path> expectedStringFiles = ImmutableList.of(
        Paths.get("test3/res/values/strings.xml"),
        Paths.get("test3/res/values-es/strings.xml"),
        Paths.get("test3/res/values-es-rES/strings.xml"),
        Paths.get("test/res/values/strings.xml"),
        Paths.get("test/res/values-es/strings.xml"),
        Paths.get("test/res/values-es-rES/strings.xml"),
        Paths.get("test2/res/values/strings.xml"),
        Paths.get("test2/res/values-es/strings.xml"),
        Paths.get("test2/res/values-es-rES/strings.xml"));

    assertIterablesEquals(expectedStringFiles, stringFilesBuilder.build());
  }

  @Test
  public void testWhitelistedStringsAreIgnored() {
    setUpFakeFilesystem(
        ImmutableSet.<Path>of(
            Paths.get("test/res/values/strings.xml"),
            Paths.get("test/res/values-es/strings.xml"),
            Paths.get("test/res/values-es-rES/strings.xml"),
            Paths.get("whitelisted/path/res/values/strings.xml"),
            Paths.get("whitelisted/path/res/values-es/strings.xml"),
            Paths.get("whitelisted/path/res/values-es-rES/strings.xml"),
            Paths.get("test3/res/values/strings.xml"),
            Paths.get("test3/res/values-es/strings.xml"),
            Paths.get("test3/res/values-es-rES/strings.xml"),
            Paths.get("test3/res/values/dimens.xml")));

    ImmutableList<Path> expectedStringFiles = ImmutableList.of(
        Paths.get("test3/res/values/strings.xml"),
        Paths.get("test3/res/values-es/strings.xml"),
        Paths.get("test3/res/values-es-rES/strings.xml"),
        Paths.get("test/res/values/strings.xml"),
        Paths.get("test/res/values-es/strings.xml"),
        Paths.get("test/res/values-es-rES/strings.xml"));

    ImmutableList.Builder<Path> stringFilesBuilder = ImmutableList.builder();
    GetStringsFilesStep step = new GetStringsFilesStep(
        filesystem,
        ImmutableList.of(Paths.get("test3"), Paths.get("test"), Paths.get("whitelisted/path")),
        stringFilesBuilder,
        ImmutableSet.of(Paths.get("whitelisted")));

    assertEquals(0, step.execute(context));

    assertIterablesEquals(expectedStringFiles, stringFilesBuilder.build());
  }

  @Test
  public void testStringsPathRegex() {
    assertTrue(matchesRegex("res/values-es/strings.xml"));
    assertTrue(matchesRegex("res/values/strings.xml"));
    assertFalse(matchesRegex("res/values-/strings.xml"));
    assertTrue(matchesRegex("/res/values-es/strings.xml"));
    assertFalse(matchesRegex("rootres/values-es/strings.xml"));
    assertTrue(matchesRegex("root/res/values-es-rUS/strings.xml"));
  }

  private static boolean matchesRegex(String input) {
    return GetStringsFilesStep.STRINGS_FILE_PATH.matcher(input).matches();
  }
}

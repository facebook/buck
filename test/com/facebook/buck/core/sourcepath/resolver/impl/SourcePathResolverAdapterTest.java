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

package com.facebook.buck.core.sourcepath.resolver.impl;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.NoSuchElementException;
import org.easymock.EasyMock;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(EasyMockRunner.class)
public class SourcePathResolverAdapterTest {

  @Rule public final ExpectedException exception = ExpectedException.none();

  @Mock private SourcePathResolver mockResolver;
  @Mock private SourcePath mockSourcePath;;
  private ProjectFilesystem projectFilesystem;
  private SourcePathResolverAdapter testAdapter;

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    testAdapter = new SourcePathResolverAdapter(mockResolver);
  }

  @Test
  public void delegatesToResolver() {
    // Only tests the methods that adds some sort of assertion in the adapter and doesn't test
    // one-liner delegating methods
    EasyMock.expect(mockResolver.getAbsolutePath(mockSourcePath)).andReturn(getMockPaths(1, 50));
    EasyMock.expect(mockResolver.getRelativePath(mockSourcePath)).andReturn(getMockPaths(1, 90));
    EasyMock.expect(mockResolver.getIdeallyRelativePath(mockSourcePath))
        .andReturn(getMockPaths(1, -10));
    EasyMock.expect(mockResolver.getRelativePath(projectFilesystem, mockSourcePath))
        .andReturn(getMockPaths(1, 100));
    EasyMock.expect(
            mockResolver.getMappedPaths(
                ImmutableMap.of("tee", mockSourcePath, "hee", mockSourcePath)))
        .andReturn(ImmutableMap.of("tee", getMockPaths(1, 99), "hee", getMockPaths(1, 101)));
    EasyMock.replay(mockResolver);

    assertThat(testAdapter.getAbsolutePath(mockSourcePath), Matchers.contains(Paths.get("path50")));
    assertThat(testAdapter.getRelativePath(mockSourcePath), Matchers.contains(Paths.get("path90")));
    assertThat(
        testAdapter.getIdeallyRelativePath(mockSourcePath),
        Matchers.contains(Paths.get("path-10")));
    assertThat(
        testAdapter.getRelativePath(projectFilesystem, mockSourcePath),
        Matchers.contains(Paths.get("path100")));
    assertThat(
        testAdapter.getMappedPaths(ImmutableMap.of("tee", mockSourcePath, "hee", mockSourcePath)),
        Matchers.equalTo(ImmutableMap.of("tee", Paths.get("path99"), "hee", Paths.get("path101"))));
  }

  @Test
  public void getAbsolutePathThrowsIfNotOneElement() {
    exception.expect(Matchers.instanceOf(NoSuchElementException.class));
    EasyMock.expect(mockResolver.getAbsolutePath(mockSourcePath)).andReturn(getMockPaths(0, 50));
    EasyMock.replay(mockResolver);
    testAdapter.getAbsolutePath(mockSourcePath);
  }

  @Test
  public void getRelativePathThrowsIfNotOneElement() {
    exception.expect(Matchers.instanceOf(IllegalArgumentException.class));
    exception.expectMessage("expected one element but was: <path90, path91>");
    EasyMock.expect(mockResolver.getRelativePath(mockSourcePath)).andReturn(getMockPaths(2, 90));
    EasyMock.replay(mockResolver);
    testAdapter.getRelativePath(mockSourcePath);
  }

  @Test
  public void getIdeallyRelativePathThrowsThrowsIfNotOneElement() {
    exception.expect(Matchers.instanceOf(IllegalArgumentException.class));
    exception.expectMessage(
        "expected one element but was: <path-10, path-5, path-6, path-7, path-8, ...>");
    EasyMock.expect(mockResolver.getIdeallyRelativePath(mockSourcePath))
        .andReturn(getMockPaths(6, -10));
    EasyMock.replay(mockResolver);
    testAdapter.getIdeallyRelativePath(mockSourcePath);
  }

  @Test
  public void getRelativePathThrowsThrowsIfNotOneElement() {
    exception.expect(Matchers.instanceOf(IllegalArgumentException.class));
    exception.expectMessage("expected one element but was: <path100, path101>");
    EasyMock.expect(mockResolver.getRelativePath(projectFilesystem, mockSourcePath))
        .andReturn(getMockPaths(2, 100));
    EasyMock.replay(mockResolver);
    testAdapter.getRelativePath(projectFilesystem, mockSourcePath);
  }

  @Test
  public void getMappedPathsThrowsIfNotOneElement() {
    exception.expect(Matchers.instanceOf(IllegalArgumentException.class));
    exception.expectMessage("expected one element but was: <path101, path102, path103>");
    EasyMock.expect(
            mockResolver.getMappedPaths(
                ImmutableMap.of("tee", mockSourcePath, "hee", mockSourcePath)))
        .andReturn(ImmutableMap.of("tee", getMockPaths(1, 99), "hee", getMockPaths(3, 101)));
    EasyMock.replay(mockResolver);
    testAdapter.getMappedPaths(ImmutableMap.of("tee", mockSourcePath, "hee", mockSourcePath));
  }

  private static ImmutableSortedSet<Path> getMockPaths(int size, int startIndex) {
    ImmutableSortedSet.Builder<Path> builder = ImmutableSortedSet.naturalOrder();
    for (int i = 0; i < size; i++) {
      builder.add(Paths.get("path" + startIndex++));
    }
    return builder.build();
  }
}

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

package com.facebook.buck.io;

import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Unit tests for {@link PathListing}.
 */
public class PathListingTest {
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  private Path oldest;
  private Path middle;
  private Path newest;

  private void setupPaths(int middleFileSize) throws IOException {
    oldest = tmpDir.newFile("oldest").toPath();

    // Many filesystems only support second granularity for last-modified time.
    Files.setLastModifiedTime(oldest, FileTime.fromMillis(1000));

    middle = tmpDir.getRoot().toPath().resolve("middle");
    Files.write(middle, Strings.repeat("X", middleFileSize).getBytes(Charsets.UTF_8));
    Files.setLastModifiedTime(middle, FileTime.fromMillis(2000));

    newest = tmpDir.newFile("newest").toPath();
    Files.setLastModifiedTime(newest, FileTime.fromMillis(3000));
  }

  @Test
  public void noPathsReturnsEmptyInclude() throws IOException {
    assertThat(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.INCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        empty());
  }

  @Test
  public void noPathsReturnsEmptyExclude() throws IOException {
    assertThat(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        empty());
  }

  @Test
  public void listsEmptyIncludeZeroNumPaths() throws IOException {
    setupPaths(0);
    assertThat(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.INCLUDE,
            Optional.of(0), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        empty());
  }

  @Test
  public void listsAllExcludeZeroNumPaths() throws IOException {
    setupPaths(0);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            Optional.of(0), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        ImmutableSet.of(newest, middle, oldest));
  }

  @Test
  public void listsOneIncludeOneNumPaths() throws IOException {
    setupPaths(0);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.INCLUDE,
            Optional.of(1), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        ImmutableSet.of(newest));
  }

  @Test
  public void listsTwoExcludeOneNumPaths() throws IOException {
    setupPaths(0);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            Optional.of(1), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        ImmutableSet.of(middle, oldest));
  }

  @Test
  public void listsAllIncludeMaxIntPaths() throws IOException {
    setupPaths(0);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.INCLUDE,
            Optional.of(Integer.MAX_VALUE), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        ImmutableSet.of(newest, middle, oldest));
  }

  @Test
  public void listsNoneExcludeMaxIntPaths() throws IOException {
    setupPaths(0);
    assertThat(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            Optional.of(Integer.MAX_VALUE), // maxPathsFilter
            Optional.<Long>absent()), // maxSizeFilter
        empty());
  }

  @Test
  public void listsEmptyIncludeZeroSize() throws IOException {
    setupPaths(10);
    assertThat(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.INCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.of(0L)), // maxSizeFilter
        empty());
  }

  @Test
  public void listsAllExcludeZeroSize() throws IOException {
    setupPaths(10);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.of(0L)), // maxSizeFilter
        ImmutableSet.of(newest, middle, oldest));
  }

  @Test
  public void listsOneIncludeOneSize() throws IOException {
    setupPaths(10);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.INCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.of(10L)), // maxSizeFilter
        ImmutableSet.of(newest));
  }

  @Test
  public void listsTwoExcludeOneSize() throws IOException {
    setupPaths(10);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.of(10L)), // maxSizeFilter
        ImmutableSet.of(middle, oldest));
  }

  @Test
  public void listsAllIncludeMaxLongSize() throws IOException {
    setupPaths(10);
    assertEquals(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.INCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.of(Long.MAX_VALUE)), // maxSizeFilter
        ImmutableSet.of(newest, middle, oldest));
  }

  @Test
  public void listsNoneExcludeMaxLongSize() throws IOException {
    setupPaths(10);
    assertThat(
        PathListing.listMatchingPathsWithFilters(
            tmpDir.getRoot().toPath(),
            "*",
            PathListing.GET_PATH_MODIFIED_TIME,
            PathListing.FilterMode.EXCLUDE,
            Optional.<Integer>absent(), // maxPathsFilter
            Optional.of(Long.MAX_VALUE)), // maxSizeFilter
        empty());
  }
}

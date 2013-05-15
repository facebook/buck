/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.File;
import java.util.Set;

public class JavaTestRuleTest {

  @Test
  public void testGetClassNamesForSources() {
    File classesFolder = new File("testdata/javatestrule/default.jar");
    Set<String> sources = ImmutableSet.of("src/com/facebook/DummyTest.java");
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithInnerClasses() {
    File classesFolder = new File("testdata/javatestrule/case1.jar");
    Set<String> sources = ImmutableSet.of("src/com/facebook/DummyTest.java");
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithMultipleTopLevelClasses() {
    File classesFolder = new File("testdata/javatestrule/case2.jar");
    Set<String> sources = ImmutableSet.of("src/com/facebook/DummyTest.java");
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithImperfectHeuristic() {
    File classesFolder = new File("testdata/javatestrule/case2fail.jar");
    Set<String> sources = ImmutableSet.of(
        "src/com/facebook/feed/DummyTest.java",
        "src/com/facebook/nav/OtherDummyTest.java");
    Set<String> classNames = JavaTestRule.CompiledClassFileFinder.getClassNamesForSources(sources, classesFolder);
    assertEquals("Ideally, if the implementation of getClassNamesForSources() were tightened up,"
        + " the set would not include com.facebook.feed.OtherDummyTest because"
        + " it was not specified in sources.",
        ImmutableSet.of(
            "com.facebook.feed.DummyTest",
            "com.facebook.feed.OtherDummyTest",
            "com.facebook.nav.OtherDummyTest"
        ),
        classNames);
  }
}

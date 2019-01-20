/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij.aggregation;

import static com.facebook.buck.features.project.intellij.aggregation.AggregationTreeNodeTest.createModule;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.features.project.intellij.IjTestProjectConfig;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class AggregationTreeTest {

  private void addModule(Path modulePath, IjModuleType moduleType, String aggregationTag) {
    aggregationTree.addModule(modulePath, createModule(modulePath, moduleType, aggregationTag));
  }

  private void addModule(Path modulePath, IjModuleType moduleType) {
    addModule(modulePath, moduleType, "");
  }

  AggregationTree aggregationTree;

  @Before
  public void setUp() {
    aggregationTree =
        new AggregationTree(
            IjTestProjectConfig.create(),
            createModule(Paths.get(""), IjModuleType.UNKNOWN_MODULE, ""));
  }

  @Test
  public void testGetModules() {
    addModule(Paths.get("a/b/c"), IjModuleType.UNKNOWN_MODULE);
    addModule(Paths.get("a/b/d"), IjModuleType.UNKNOWN_MODULE);
    addModule(Paths.get("a/b/e"), IjModuleType.UNKNOWN_MODULE);
    addModule(Paths.get("a/c/a"), IjModuleType.UNKNOWN_MODULE);
    addModule(Paths.get("a/c/e"), IjModuleType.UNKNOWN_MODULE);
    addModule(Paths.get("a/c"), IjModuleType.UNKNOWN_MODULE);
    addModule(Paths.get("a/d/e"), IjModuleType.UNKNOWN_MODULE);

    assertEquals(
        ImmutableSet.of(
            Paths.get("a/b/c"),
            Paths.get("a/b/d"),
            Paths.get("a/b/e"),
            Paths.get("a/c/a"),
            Paths.get("a/c/e"),
            Paths.get("a/c"),
            Paths.get("a/d/e"),
            Paths.get("")),
        aggregationTree
            .getModules()
            .stream()
            .map(AggregationModule::getModuleBasePath)
            .collect(ImmutableSet.toImmutableSet()));
  }

  @Test
  public void testAggregateModules() {
    addModule(Paths.get("a/b/c"), IjModuleType.ANDROID_MODULE, "tag1");
    addModule(Paths.get("a/b/d"), IjModuleType.JAVA_MODULE, "tag2");
    addModule(Paths.get("a/b/e"), IjModuleType.UNKNOWN_MODULE, "tag3");
    addModule(Paths.get("a/b/f"), IjModuleType.UNKNOWN_MODULE, "tag3");

    addModule(Paths.get("a/c"), IjModuleType.ANDROID_MODULE, "tag1");
    addModule(Paths.get("a/c/a"), IjModuleType.JAVA_MODULE, "tag2");
    addModule(Paths.get("a/c/a/a"), IjModuleType.ANDROID_MODULE, "tag1");
    addModule(Paths.get("a/c/a/b"), IjModuleType.JAVA_MODULE, "tag2");
    addModule(Paths.get("a/c/a/b/a"), IjModuleType.ANDROID_MODULE, "tag1");
    addModule(Paths.get("a/c/a/b/b"), IjModuleType.ANDROID_MODULE, "tag1");
    addModule(Paths.get("a/c/a/c"), IjModuleType.ANDROID_MODULE, "tag1");

    addModule(Paths.get("a/d/e"), IjModuleType.ANDROID_RESOURCES_MODULE, "tag1");
    addModule(Paths.get("a/d/f"), IjModuleType.ANDROID_RESOURCES_MODULE, "tag1");
    addModule(Paths.get("a/d/g"), IjModuleType.JAVA_MODULE, "tag2");
    addModule(Paths.get("a/d/h"), IjModuleType.JAVA_MODULE, "tag2");

    addModule(Paths.get("a/e/e"), IjModuleType.ANDROID_MODULE, "tag1");
    addModule(Paths.get("a/e/f"), IjModuleType.ANDROID_MODULE, "tag2");
    addModule(Paths.get("a/e/g"), IjModuleType.JAVA_MODULE, "tag3");
    addModule(Paths.get("a/e/h"), IjModuleType.JAVA_MODULE, "tag3");

    aggregationTree.aggregateModules(2);

    assertEquals(
        ImmutableSet.of(
            createModule(Paths.get("a/b"), IjModuleType.ANDROID_MODULE, "tag1"),
            createModule(Paths.get("a/b/d"), IjModuleType.JAVA_MODULE, "tag2"),
            createModule(Paths.get("a/c"), IjModuleType.ANDROID_MODULE, "tag1"),
            createModule(Paths.get("a/c/a"), IjModuleType.JAVA_MODULE, "tag2"),
            createModule(Paths.get("a/c/a/a"), IjModuleType.ANDROID_MODULE, "tag1"),
            createModule(Paths.get("a/c/a/c"), IjModuleType.ANDROID_MODULE, "tag1"),
            createModule(Paths.get("a/c/a/b/a"), IjModuleType.ANDROID_MODULE, "tag1"),
            createModule(Paths.get("a/c/a/b/b"), IjModuleType.ANDROID_MODULE, "tag1"),
            createModule(Paths.get("a/d"), IjModuleType.JAVA_MODULE, "tag2"),
            createModule(Paths.get("a/d/e"), IjModuleType.ANDROID_RESOURCES_MODULE, "tag1"),
            createModule(Paths.get("a/d/f"), IjModuleType.ANDROID_RESOURCES_MODULE, "tag1"),
            createModule(Paths.get("a/e"), IjModuleType.JAVA_MODULE, "tag3"),
            createModule(Paths.get("a/e/e"), IjModuleType.ANDROID_MODULE, "tag1"),
            createModule(Paths.get("a/e/f"), IjModuleType.ANDROID_MODULE, "tag2"),
            createModule(Paths.get(""), IjModuleType.UNKNOWN_MODULE)),
        ImmutableSet.copyOf(aggregationTree.getModules()));
  }
}

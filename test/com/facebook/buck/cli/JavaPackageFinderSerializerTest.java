/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.jvm.java.JavaPackageFinderSerializer;
import com.facebook.buck.jvm.java.ResourcesRootPackageFinder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class JavaPackageFinderSerializerTest {
  @Test
  public void testSerializingAndDeserializing() throws Exception {
    DefaultJavaPackageFinder defaultFinder =
        new DefaultJavaPackageFinder(
            ImmutableSortedSet.of("paths", "from", "root"), ImmutableSet.of("path", "elements"));
    ResourcesRootPackageFinder resourcesRootFinder =
        new ResourcesRootPackageFinder(Paths.get("/path/to/res/root"), defaultFinder);

    Map<String, Object> data = JavaPackageFinderSerializer.serialize(resourcesRootFinder);
    JavaPackageFinder result = JavaPackageFinderSerializer.deserialize(data);

    assertThat(result, Matchers.instanceOf(ResourcesRootPackageFinder.class));
    ResourcesRootPackageFinder outResFinder = (ResourcesRootPackageFinder) result;
    assertThat(
        outResFinder.getResourcesRoot(),
        Matchers.equalToObject(resourcesRootFinder.getResourcesRoot()));

    JavaPackageFinder chainedFinder = outResFinder.getFallbackFinder();
    assertThat(chainedFinder, Matchers.instanceOf(DefaultJavaPackageFinder.class));
    DefaultJavaPackageFinder outDefFinder = (DefaultJavaPackageFinder) chainedFinder;
    assertThat(
        outDefFinder.getPathElements(), Matchers.equalToObject(defaultFinder.getPathElements()));
    assertThat(
        outDefFinder.getPathsFromRoot(), Matchers.equalToObject(defaultFinder.getPathsFromRoot()));
  }
}

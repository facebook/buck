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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.model.BuildTargetFactory;
import com.google.common.collect.ImmutableSortedSet;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HasJavaAbiTest {
  @Test
  public void testGetAbiTarget() {
    assertThat(
        getDummyHasJavaAbi(BuildTargetFactory.newInstance("//foo/bar:bar")).getAbiJar().get(),
        Matchers.equalTo(BuildTargetFactory.newInstance("//foo/bar:bar#class-abi")));
  }

  @Test
  public void testGetAbiTargetFlavored() {
    assertThat(
        getDummyHasJavaAbi(BuildTargetFactory.newInstance("//foo/bar:bar#baz")).getAbiJar().get(),
        Matchers.equalTo(BuildTargetFactory.newInstance("//foo/bar:bar#baz,class-abi")));
  }

  private HasJavaAbi getDummyHasJavaAbi(BuildTarget target) {
    return new HasJavaAbi() {
      @Override
      public BuildTarget getBuildTarget() {
        return target;
      }

      @Override
      public ImmutableSortedSet<SourcePath> getJarContents() {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean jarContains(String path) {
        throw new UnsupportedOperationException();
      }
    };
  }
}

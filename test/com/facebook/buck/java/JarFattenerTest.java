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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class JarFattenerTest {

  private JarFattener createJarFattener(Optional<String> javaBinOverride) {
    BuildTarget target = BuildTargetFactory.newInstance("//:foo");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    SourcePath sourcePath = new BuildTargetSourcePath(target);
    return new JarFattener(
        params,
        new SourcePathResolver(new BuildRuleResolver()),
        JavacOptions.builder().setSourceLevel("8").setTargetLevel("8").build(),
        sourcePath,
        ImmutableMap.<String, SourcePath>of(),
        javaBinOverride);
  }

  @Test
  public void testGetExecutableCommand() {
    JarFattener jarFattener = createJarFattener(Optional.<String>absent());

    BuildRuleResolver buildResolver = new BuildRuleResolver();
    SourcePathResolver sourceResolver = new SourcePathResolver(buildResolver);
    buildResolver.addToIndex(jarFattener);

    BuildTargetSourcePath jarSourcePath = new BuildTargetSourcePath(jarFattener.getBuildTarget());
    String expectedJarPath = sourceResolver.getResolvedPath(jarSourcePath).toString();
    ImmutableList<String> expected = ImmutableList.of(
       "java",
       "-jar",
       expectedJarPath);

    ImmutableList<String> args = jarFattener
      .getExecutableCommand()
      .getCommandPrefix(sourceResolver);

    assertThat(args, is(equalTo(expected)));
  }

  @Test
  public void testGetExecutableCommandWithJavaBinOverride() {
    String javaBin = "/usr/bin/my_java_wrapper.sh";

    JarFattener jarFattener = createJarFattener(Optional.of(javaBin));

    BuildRuleResolver buildResolver = new BuildRuleResolver();
    SourcePathResolver sourceResolver = new SourcePathResolver(buildResolver);
    buildResolver.addToIndex(jarFattener);

    BuildTargetSourcePath jarSourcePath = new BuildTargetSourcePath(jarFattener.getBuildTarget());
    String expectedJarPath = sourceResolver.getResolvedPath(jarSourcePath).toString();
    ImmutableList<String> expected = ImmutableList.of(
       "java",
       "-Dbuck.java.java_bin=/usr/bin/my_java_wrapper.sh",
       "-jar",
       expectedJarPath);

    ImmutableList<String> args = jarFattener
      .getExecutableCommand()
      .getCommandPrefix(sourceResolver);

    assertThat(args, is(equalTo(expected)));
  }
}

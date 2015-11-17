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

package com.facebook.buck.shell;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Test;

public class ShTestDescriptionTest {

  @Test
  public void argsWithLocationMacro() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);
    ShTest shTest =
        (ShTest) new ShTestBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setTest(new FakeSourcePath("test.sh"))
            .setArgs(ImmutableList.of("$(location //:dep)"))
            .build(resolver);
    assertThat(
        shTest.getDeps(),
        Matchers.contains(dep));
    assertThat(
        FluentIterable.from(shTest.getArgs())
            .transform(Arg.stringifyFunction()),
        Matchers.contains(
            pathResolver.getAbsolutePath(
                new BuildTargetSourcePath(dep.getBuildTarget())).toString()));
  }

}

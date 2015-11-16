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

package com.facebook.buck.rules.args;

import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;

import org.hamcrest.Matchers;
import org.junit.Test;

public class SourcePathArgTest {

  @Test
  public void stringify() {
    SourcePath path = new FakeSourcePath("something");
    SourcePathArg arg = new SourcePathArg(new SourcePathResolver(new BuildRuleResolver()), path);
    assertThat(arg.stringify(), Matchers.equalTo("something"));
  }

  @Test
  public void getDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Genrule rule =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setOut("output")
            .build(resolver);
    SourcePathArg arg =
        new SourcePathArg(pathResolver, new BuildTargetSourcePath(rule.getBuildTarget()));
    assertThat(arg.getDeps(pathResolver), Matchers.<BuildRule>contains(rule));
  }

}

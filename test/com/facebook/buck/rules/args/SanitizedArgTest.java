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

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Functions;

import org.hamcrest.Matchers;
import org.junit.Test;

public class SanitizedArgTest {

  private RuleKeyBuilder createRuleKeyBuilder() {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    FileHashCache fileHashCache = new DefaultFileHashCache(projectFilesystem);
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );
    return new RuleKeyBuilder(
        resolver,
        new DefaultFileHashCache(projectFilesystem),
        new DefaultRuleKeyBuilderFactory(fileHashCache, resolver));
  }

  @Test
  public void stringify() {
    SanitizedArg arg = new SanitizedArg(Functions.constant("sanitized"), "unsanitized");
    assertThat(
        Arg.stringListFunction().apply(arg),
        Matchers.contains("unsanitized"));
  }

  @Test
  public void appendToRuleKey() {
    SanitizedArg arg1 = new SanitizedArg(Functions.constant("sanitized"), "unsanitized 1");
    SanitizedArg arg2 = new SanitizedArg(Functions.constant("sanitized"), "unsanitized 2");
    assertThat(
        arg1.appendToRuleKey(createRuleKeyBuilder()).build(),
        Matchers.equalTo(arg2.appendToRuleKey(createRuleKeyBuilder()).build()));
  }

}

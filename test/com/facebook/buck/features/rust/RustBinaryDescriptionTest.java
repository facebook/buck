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

package com.facebook.buck.features.rust;

import com.facebook.buck.cxx.CxxGenruleBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Test;

public class RustBinaryDescriptionTest {

  @Test
  public void testGeneratedSourceFromCxxGenrule() throws NoSuchBuildTargetException {
    CxxGenruleBuilder srcBuilder =
        new CxxGenruleBuilder(BuildTargetFactory.newInstance("//:src")).setOut("main.rs");
    RustBinaryBuilder binaryBuilder =
        RustBinaryBuilder.from("//:bin")
            .setSrcs(
                ImmutableSortedSet.of(DefaultBuildTargetSourcePath.of(srcBuilder.getTarget())));
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(srcBuilder.build(), binaryBuilder.build());
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver(targetGraph);
    ruleResolver.requireRule(binaryBuilder.getTarget());
  }
}

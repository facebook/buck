/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.starlark.rule.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import org.junit.Test;

public class SkylarkDependencyTest {

  private TestMutableEnv env(SkylarkDependency dep) {
    FakeBuiltInProvider fakeInfoProvider = new FakeBuiltInProvider("FakeInfo");
    return new TestMutableEnv(
        ImmutableMap.of(
            "dep", dep, "DefaultInfo", DefaultInfo.PROVIDER, "FakeInfo", fakeInfoProvider));
  }

  @Test
  public void returnsLabel() throws InterruptedException, EvalException {
    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of())));

    try (TestMutableEnv env = env(dep)) {
      boolean labelString =
          (boolean)
              BuildFileAST.eval(
                  env.getEnv(), "dep.label.name == \"bar\" and dep.label.package == \"foo\"");
      assertTrue(labelString);
    }
  }

  @Test
  public void repr() {
    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of())));

    assertEquals("<dependency //foo:bar>", Printer.repr(dep));
  }

  @Test
  public void byIndexWorks() throws InterruptedException, EvalException {

    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of())));

    try (TestMutableEnv env = env(dep)) {
      boolean presentProvider =
          (boolean) BuildFileAST.eval(env.getEnv(), "len(dep[DefaultInfo].default_outputs) == 0");
      boolean missingProvider = (boolean) BuildFileAST.eval(env.getEnv(), "dep[FakeInfo] == None");
      assertTrue(presentProvider);
      assertTrue(missingProvider);
    }
  }

  @Test
  public void containsWorks() throws InterruptedException, EvalException {
    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of())));

    try (TestMutableEnv env = env(dep)) {
      boolean presentProvider = (boolean) BuildFileAST.eval(env.getEnv(), "DefaultInfo in dep");
      boolean missingProvider = (boolean) BuildFileAST.eval(env.getEnv(), "FakeInfo in dep");
      assertTrue(presentProvider);
      assertFalse(missingProvider);
    }
  }
}

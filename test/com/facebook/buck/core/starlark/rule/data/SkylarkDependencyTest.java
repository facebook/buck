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
import com.facebook.buck.core.starlark.testutil.TestStarlarkParser;
import com.google.common.collect.ImmutableMap;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkList;
import org.junit.Test;

public class SkylarkDependencyTest {

  private TestMutableEnv env(SkylarkDependency dep) {
    FakeBuiltInProvider fakeInfoProvider = new FakeBuiltInProvider("FakeInfo");
    return new TestMutableEnv(
        ImmutableMap.of(
            "dep", dep, "DefaultInfo", DefaultInfo.PROVIDER, "FakeInfo", fakeInfoProvider));
  }

  @Test
  public void returnsLabel() throws Exception {
    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(Dict.empty(), StarlarkList.empty())));

    try (TestMutableEnv env = env(dep)) {
      boolean labelString =
          (boolean)
              TestStarlarkParser.eval(
                  env.getEnv(),
                  env.getModule(),
                  "dep.label.name == \"bar\" and dep.label.package == \"foo\"");
      assertTrue(labelString);
    }
  }

  @Test
  public void repr() {
    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(Dict.empty(), StarlarkList.empty())));

    assertEquals("<dependency //foo:bar>", new Printer().repr(dep).toString());
  }

  @Test
  public void byIndexWorks() throws Exception {

    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(Dict.empty(), StarlarkList.empty())));

    try (TestMutableEnv env = env(dep)) {
      boolean presentProvider =
          (boolean)
              TestStarlarkParser.eval(
                  env.getEnv(), env.getModule(), "len(dep[DefaultInfo].default_outputs) == 0");
      boolean missingProvider =
          (boolean) TestStarlarkParser.eval(env.getEnv(), env.getModule(), "dep[FakeInfo] == None");
      assertTrue(presentProvider);
      assertTrue(missingProvider);
    }
  }

  @Test
  public void containsWorks() throws Exception {
    SkylarkDependency dep =
        new SkylarkDependency(
            BuildTargetFactory.newInstance("//foo:bar"),
            ProviderInfoCollectionImpl.builder()
                .build(new ImmutableDefaultInfo(Dict.empty(), StarlarkList.empty())));

    try (TestMutableEnv env = env(dep)) {
      boolean presentProvider =
          (boolean) TestStarlarkParser.eval(env.getEnv(), env.getModule(), "DefaultInfo in dep");
      boolean missingProvider =
          (boolean) TestStarlarkParser.eval(env.getEnv(), env.getModule(), "FakeInfo in dep");
      assertTrue(presentProvider);
      assertFalse(missingProvider);
    }
  }
}

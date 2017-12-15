/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.python.toolchain;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

@Value.Immutable(builder = false, copy = false)
@BuckStyleImmutable
public abstract class AbstractPythonVersion implements AddsToRuleKey {

  // TODO(cjhopman): This should add the interpreter name to the rulekey.
  @Value.Parameter
  public abstract String getInterpreterName();

  @Value.Parameter
  @AddToRuleKey
  public abstract String getVersionString(); // X.Y

  @Override
  public String toString() {
    return getInterpreterName() + " " + getVersionString();
  }
}

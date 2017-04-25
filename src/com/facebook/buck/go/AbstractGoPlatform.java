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

package com.facebook.buck.go;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractGoPlatform implements FlavorConvertible, RuleKeyAppendable {
  abstract String getGoOs();

  abstract String getGoArch();

  abstract Optional<CxxPlatform> getCxxPlatform();

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("goos", getGoOs()).setReflectively("goarch", getGoArch());
  }

  @Override
  public Flavor getFlavor() {
    return InternalFlavor.of(getGoOs() + "_" + getGoArch());
  }
}

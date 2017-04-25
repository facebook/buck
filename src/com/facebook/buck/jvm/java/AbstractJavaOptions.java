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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJavaOptions implements RuleKeyAppendable {

  public JavaRuntimeLauncher getJavaRuntimeLauncher() {
    Optional<Path> javaPath = getJavaPath();
    if (javaPath.isPresent()) {
      return new ExternalJavaRuntimeLauncher(javaPath.get().toString());
    }
    return new ExternalJavaRuntimeLauncher("java");
  }

  protected abstract Optional<Path> getJavaPath();

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("java", getJavaRuntimeLauncher());
  }
}

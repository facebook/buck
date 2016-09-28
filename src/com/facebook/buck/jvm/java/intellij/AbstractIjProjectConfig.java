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
package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.util.Map;

import javax.annotation.Nullable;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjProjectConfig {

  @Value.Default
  public boolean isAutogenerateAndroidFacetSourcesEnabled() {
    return true;
  }

  public abstract Map<String, String> getJdkAliases();

  public @Nullable String getJdkAlias(@Nullable String jdkName) {
    String alias = getJdkAliases().get(jdkName);

    if (alias == null) {
      return jdkName;
    } else {
      return alias;
    }
  }

}

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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

/** Used to provide extra classpath entries to a compiler. */
public interface ExtraClasspathProvider extends AddsToRuleKey {

  /** @return extra classpath entries as {@link AbsPath}s */
  Iterable<AbsPath> getExtraClasspath();

  ExtraClasspathProvider EMPTY = new EmptyExtraClasspathProvider();

  class EmptyExtraClasspathProvider implements ExtraClasspathProvider {

    @AddToRuleKey private final String classpath = "default";

    private EmptyExtraClasspathProvider() {}

    @Override
    public Iterable<AbsPath> getExtraClasspath() {
      return ImmutableList.of();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      return o != null && getClass() == o.getClass();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(classpath);
    }
  }
}

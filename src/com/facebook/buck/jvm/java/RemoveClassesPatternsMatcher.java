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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.google.common.collect.ImmutableSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public class RemoveClassesPatternsMatcher implements AddsToRuleKey, Predicate<Object> {
  public static final RemoveClassesPatternsMatcher EMPTY =
      new RemoveClassesPatternsMatcher(ImmutableSet.of());

  @AddToRuleKey private final ImmutableSet<Pattern> patterns;

  public RemoveClassesPatternsMatcher(ImmutableSet<Pattern> patterns) {
    this.patterns = patterns;
  }

  private boolean shouldRemoveClass(ZipEntry entry) {
    if (patterns.isEmpty() || !entry.getName().endsWith(".class")) {
      return false;
    }

    return shouldRemoveClass(pathToClassName(entry.getName()));
  }

  private boolean shouldRemoveClass(String className) {
    if (patterns.isEmpty()) {
      return false;
    }

    for (Pattern pattern : patterns) {
      if (pattern.matcher(className).find()) {
        return true;
      }
    }

    return false;
  }

  /** This method is for serialization only. */
  /* package */ ImmutableSet<Pattern> getPatterns() {
    return patterns;
  }

  private static String pathToClassName(String classFilePath) {
    return classFilePath.replace('/', '.').replace(".class", "");
  }

  @Override
  public boolean test(Object o) {
    if (o instanceof ZipEntry) {
      return shouldRemoveClass((ZipEntry) o);
    } else {
      return shouldRemoveClass((String) o);
    }
  }
}

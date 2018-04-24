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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

class StringifyAlterRuleKey implements AlterRuleKey {

  private static final Logger LOG = Logger.get(StringifyAlterRuleKey.class);

  private final ValueExtractor valueExtractor;

  StringifyAlterRuleKey(ValueExtractor valueExtractor) {
    this.valueExtractor = valueExtractor;
  }

  @VisibleForTesting
  static Iterable<Path> findAbsolutePaths(Object val) {
    if (val instanceof Path) {
      Path path = (Path) val;
      if (path.isAbsolute()) {
        return Collections.singleton(path);
      }
    } else if (val instanceof PathSourcePath) {
      return findAbsolutePaths(((PathSourcePath) val).getRelativePath());
    } else if (val instanceof Iterable) {
      return FluentIterable.from((Iterable<?>) val)
          .transformAndConcat(StringifyAlterRuleKey::findAbsolutePaths);
    } else if (val instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) val;
      Iterable<?> allSubValues = Iterables.concat(map.keySet(), map.values());
      return FluentIterable.from(allSubValues)
          .transformAndConcat(StringifyAlterRuleKey::findAbsolutePaths);
    } else if (val instanceof Optional) {
      Optional<?> optional = (Optional<?>) val;
      if (optional.isPresent()) {
        return findAbsolutePaths(optional.get());
      }
    }

    return ImmutableList.of();
  }

  @Override
  public void amendKey(RuleKeyObjectSink sink, Object addsToRuleKey) {
    Object val = valueExtractor.getValue(addsToRuleKey);
    String stringVal = (val == null) ? null : String.valueOf(val);
    sink.setReflectively(valueExtractor.getName(), stringVal);

    if (val != null) {
      Iterable<Path> absolutePaths = findAbsolutePaths(val);
      if (!Iterables.isEmpty(absolutePaths)) {
        LOG.warn(
            "Value %s contains absolute paths %s and it is included in a rule key.",
            valueExtractor.getFullyQualifiedName(), ImmutableSet.copyOf(absolutePaths));
      }
    }
  }
}

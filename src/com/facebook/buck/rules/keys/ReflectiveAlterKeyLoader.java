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

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;

import java.lang.reflect.Field;
import java.util.Comparator;

class ReflectiveAlterKeyLoader
    extends CacheLoader<Class<? extends BuildRule>, ImmutableCollection<AlterRuleKey>> {

  private static final Comparator<Field> FIELD_COMPARATOR = new Comparator<Field>() {
    @Override
    public int compare(Field o1, Field o2) {
      String o1Name = o1.getDeclaringClass() + "." + o1.getName();
      String o2Name = o2.getDeclaringClass() + "." + o2.getName();
      return o1Name.compareTo(o2Name);
    }
  };

  @Override
  public ImmutableCollection<AlterRuleKey> load(Class<? extends BuildRule> key)
      throws Exception {
    ImmutableList.Builder<AlterRuleKey> builder = ImmutableList.builder();

    for (Class<?> current = key; !Object.class.equals(current); current = current.getSuperclass()) {
      ImmutableSortedMap.Builder<Field, AlterRuleKey> fields = ImmutableSortedMap.orderedBy(
          FIELD_COMPARATOR);
      for (final Field field : current.getDeclaredFields()) {
        field.setAccessible(true);
        final AddToRuleKey annotation = field.getAnnotation(AddToRuleKey.class);
        if (annotation == null) {
          continue;
        }

        AlterRuleKey ark;
        if (annotation.stringify()) {
          ark = new StringifyAlterRuleKey(field);
        } else if (RuleKeyAppendable.class.isAssignableFrom(field.getType())) {
          ark = new AppendingAlterRuleKey(field);
        } else {
          ark = new DefaultAlterRuleKey(field);
        }

        fields.put(field, ark);
      }
      builder.addAll(fields.build().values());
    }
    return builder.build();
  }
}

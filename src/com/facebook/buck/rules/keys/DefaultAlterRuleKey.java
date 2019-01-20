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
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;

class DefaultAlterRuleKey implements AlterRuleKey {
  private final ValueExtractor valueExtractor;

  DefaultAlterRuleKey(ValueExtractor valueExtractor) {
    this.valueExtractor = valueExtractor;
  }

  @Override
  public void amendKey(RuleKeyObjectSink builder, Object addsToRuleKey) {
    try {
      builder.setReflectively(valueExtractor.getName(), valueExtractor.getValue(addsToRuleKey));
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(e, "When amending %s.", valueExtractor.getName());
    }
  }
}

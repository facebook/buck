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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.ThreadSafe;

/** Contains functionality related to rulekey diagnostics. */
@ThreadSafe
public class RuleKeyDiagnostics<RULE_KEY, DIAG_KEY> {

  private final Set<Object> computed = Sets.newConcurrentHashSet();
  private final Function<BuildRule, Result<RULE_KEY, DIAG_KEY>> ruleResultSupplier;
  private final Function<AddsToRuleKey, Result<RULE_KEY, DIAG_KEY>> appendableResultSupplier;

  public static <RULE_KEY, DIAG_KEY> RuleKeyDiagnostics<RULE_KEY, DIAG_KEY> nop() {
    return new RuleKeyDiagnostics<RULE_KEY, DIAG_KEY>(rulke -> null, appendable -> null) {
      @Override
      public void processRule(BuildRule rule, Consumer<Result<RULE_KEY, DIAG_KEY>> consumer) {}
    };
  }

  public RuleKeyDiagnostics(
      Function<BuildRule, Result<RULE_KEY, DIAG_KEY>> ruleResultSupplier,
      Function<AddsToRuleKey, Result<RULE_KEY, DIAG_KEY>> appendableResultSupplier) {
    this.ruleResultSupplier = ruleResultSupplier;
    this.appendableResultSupplier = appendableResultSupplier;
  }

  /**
   * Computes the diagnostic rulekey data for the given rule and all of its appendables recursively.
   * Previously processed rules and appendables are skipped and not fed to the consumer. Results for
   * the newly processed rule and appendables are fed to the consumer, but not stored otherwise.
   * Only information of whether something has been processed or not gets stored.
   */
  public void processRule(BuildRule rule, Consumer<Result<RULE_KEY, DIAG_KEY>> resultConsumer) {
    if (!computed.add(rule)) {
      return;
    }
    Queue<AddsToRuleKey> appendableQueue = new LinkedList<>();
    Result<RULE_KEY, DIAG_KEY> result = ruleResultSupplier.apply(rule);
    Iterables.addAll(appendableQueue, result.appendables);
    resultConsumer.accept(result);
    while (!appendableQueue.isEmpty()) {
      AddsToRuleKey appendable = appendableQueue.remove();
      if (computed.add(appendable)) {
        result = appendableResultSupplier.apply(appendable);
        Iterables.addAll(appendableQueue, result.appendables);
        resultConsumer.accept(result);
      }
    }
  }

  public static class Result<RULE_KEY, DIAG_KEY> {
    public final RULE_KEY ruleKey;
    public final DIAG_KEY diagKey;
    final Iterable<AddsToRuleKey> appendables;

    public Result(RULE_KEY ruleKey, DIAG_KEY diagKey, Iterable<AddsToRuleKey> appendables) {
      this.ruleKey = ruleKey;
      this.diagKey = diagKey;
      this.appendables = appendables;
    }

    public static <RULE_KEY, DIAG_KEY> Result<RULE_KEY, DIAG_KEY> of(
        RULE_KEY ruleKey, RuleKeyResult<DIAG_KEY> res) {
      return new Result<>(ruleKey, res.result, Iterables.filter(res.deps, AddsToRuleKey.class));
    }
  }
}

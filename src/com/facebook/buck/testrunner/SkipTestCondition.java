/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.testrunner;

import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * To be registered as an extension at <code>
 * "META-INF/services/org.junit.jupiter.api.extension.Extension"</code>
 */
public class SkipTestCondition implements ExecutionCondition {

  private static Predicate<ExtensionContext> SKIP_FILTER;

  static void useSkipFilter(Predicate<ExtensionContext> filter) {
    SKIP_FILTER = filter;
  }

  static Predicate<ExtensionContext> getSkipFilter() {
    return Optional.ofNullable(SKIP_FILTER).orElse(c -> false);
  }

  /**
   * @param context execution context.
   * @return enabled or disabled if the execution is a dry-run.
   */
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    if (isTestExecution(context) && shouldSkip(context)) {
      return ConditionEvaluationResult.disabled("skip");
    }
    return ConditionEvaluationResult.enabled(null);
  }

  private boolean isTestExecution(ExtensionContext context) {
    return "MethodExtensionContext".equals(context.getClass().getSimpleName());
  }

  private boolean shouldSkip(ExtensionContext context) {
    return getSkipFilter().test(context);
  }
}

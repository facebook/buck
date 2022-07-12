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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.cd.params.DefaultRulesCDParams;
import com.facebook.buck.jvm.cd.params.RulesCDParams;
import javax.annotation.Nullable;

/** Utility class used to create {@link RulesCDParams} for Java rules. */
public class JavaCDParams {

  @Nullable private static RulesCDParams cdParams;

  /** Returns {@link RulesCDParams} built from configuration values */
  public static RulesCDParams get(
      JavaBuckConfig javaBuckConfig, JavaCDBuckConfig javaCDBuckConfig) {
    if (cdParams == null) {
      cdParams =
          DefaultRulesCDParams.of(
              javaBuckConfig.isJavaCDEnabled(),
              javaCDBuckConfig.getJvmFlags(),
              javaCDBuckConfig.getWorkerToolSize(),
              javaCDBuckConfig.getWorkerToolMaxInstancesSize(),
              javaCDBuckConfig.getBorrowFromPoolTimeoutInSeconds(),
              javaCDBuckConfig.getMaxWaitForResultTimeoutInSeconds(),
              javaBuckConfig.isPipeliningDisabled(),
              javaCDBuckConfig.isPassAllEnvVariablesToJavacd());
    }
    return cdParams;
  }
}

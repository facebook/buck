/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsAmender;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.RuleKeyBuilder;


class BootClasspathAppender implements JavacOptionsAmender {
  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder.setReflectively(JavacOptionsAmender.RULE_KEY_NAME, "android");
  }

  @Override
  public JavacOptions amend(JavacOptions original, BuildContext context) {
    return JavacOptions.builder(original)
        .setBootclasspath(context.getAndroidBootclasspathSupplier().get())
        .build();
  }
}

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

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;

public class KeystoreDescription implements DescriptionWithTargetGraph<KeystoreDescriptionArg> {

  @Override
  public Class<KeystoreDescriptionArg> getConstructorArgType() {
    return KeystoreDescriptionArg.class;
  }

  @Override
  public Keystore createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      KeystoreDescriptionArg args) {
    return new Keystore(
        buildTarget, context.getProjectFilesystem(), params, args.getStore(), args.getProperties());
  }

  @RuleArg
  interface AbstractKeystoreDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    SourcePath getStore();

    SourcePath getProperties();
  }
}

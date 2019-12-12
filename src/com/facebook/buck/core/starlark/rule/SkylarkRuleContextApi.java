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

package com.facebook.buck.core.starlark.rule;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;

/** The `ctx` variable that is passed to user implementation functions */
@SkylarkModule(
    name = "ctx",
    doc =
        "The ctx variable that is passed to rule implementation functions. "
            + "Provides information about dependencies, attributes, actions, etc",
    title = "ctx",
    category = SkylarkModuleCategory.BUILTIN)
interface SkylarkRuleContextApi extends SkylarkValue {
  @SkylarkCallable(
      name = "attr",
      doc = "Struct of parameters that were provided when the user instantiated this rule",
      structField = true)
  SkylarkRuleContextAttr getAttr();

  @SkylarkCallable(
      name = "label",
      doc = "The label for the target that is currently being created",
      structField = true)
  Label getLabel();

  @SkylarkCallable(
      name = "actions",
      doc = "Struct containing methods to create and interact with actions",
      structField = true)
  SkylarkRuleContextActionsApi getActions();
}

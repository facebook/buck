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

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/** The context passed to user defined rules' implementation functions */
public class SkylarkRuleContext implements SkylarkRuleContextApi {

  private final Label label;
  private final CapturingActionRegistry registry;
  private final SkylarkRuleContextAttr attr;
  private final SkylarkRuleContextActions actions;

  /**
   * Create a {@link SkylarkRuleContext} to be used in users' implementation functions
   *
   * @param context the context for the analysing this rule. Used primarily for creating and
   *     manipulating actions
   * @param label the label of the new rule being evaluated
   * @param skylarkRuleContextAttr a mapping-like representation of field names to
   *     coerced-transformed values for a given rule
   */
  public SkylarkRuleContext(
      RuleAnalysisContext context, Label label, SkylarkRuleContextAttr skylarkRuleContextAttr) {
    this.label = label;
    this.registry = new CapturingActionRegistry(context.actionRegistry());
    this.attr = skylarkRuleContextAttr;
    this.actions = new SkylarkRuleContextActions(registry);
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<ctx>");
  }

  @Override
  public SkylarkRuleContextAttr getAttr() {
    return attr;
  }

  @Override
  public Label getLabel() {
    return label;
  }

  @Override
  public SkylarkRuleContextActionsApi getActions() {
    return this.actions;
  }

  /**
   * Get a list of all Artifacts that were used in actions
   *
   * <p>This is used to infer outputs to use to create a {@link
   * com.facebook.buck.core.rules.providers.lib.DefaultInfo} object if no output attributes were
   * specified, or if no {@link com.facebook.buck.core.rules.providers.lib.DefaultInfo} object was
   * returned by a user's implementation function.
   *
   * @return List of {@link Artifact}s that were used in actions.
   */
  ImmutableSet<Artifact> getOutputs() {
    return registry.getOutputs();
  }
}

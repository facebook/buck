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

package com.facebook.buck.rules.param;

import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;

/** Special rule attribute. */
public class SpecialAttr implements ParamNameOrSpecial, Comparable<SpecialAttr> {

  public static final SpecialAttr OUTPUT_PATH =
      new SpecialAttr("buck.output_path", "buck.outputPath");
  public static final SpecialAttr GEN_SRC_PATH =
      new SpecialAttr("buck.generated_source_path", "buck.generatedSourcePath");
  public static final SpecialAttr TARGET_HASH =
      new SpecialAttr("buck.target_hash", "buck.targetHash");
  public static final SpecialAttr RULE_KEY = new SpecialAttr("buck.rule_key", "buck.ruleKey");
  public static final SpecialAttr RULE_TYPE = new SpecialAttr("buck.rule_type", "buck.ruleType");
  public static final SpecialAttr OUTPUT_PATHS =
      new SpecialAttr("buck.output_paths", "buck.outputPaths");

  /** All the dependencies of a target. */
  public static final SpecialAttr DIRECT_DEPENDENCIES = new SpecialAttr("buck.direct_dependencies");

  /** Location of the target relative to the root of the repository */
  public static final SpecialAttr BASE_PATH = new SpecialAttr("buck.base_path");

  /** Rule type. */
  public static final SpecialAttr BUCK_TYPE = new SpecialAttr("buck.type", "buck.type");

  /**
   * Configuration attached to target. This is the same value you would get in parenthesis after the
   * target name in a cquery result, but provided in an easily machine-readable format.
   *
   * <p>NOTE: At time of writing this is a human-readable platform name, but in the future we want
   * this to be a hash.
   */
  public static final SpecialAttr CONFIGURATION = new SpecialAttr("buck.configuration");

  /**
   * Target configurations (multiple) attached to target. Only used for `buck query` since the
   * MergedTargetGraph is the only place where the "same" target has multiple configurations.
   */
  public static final SpecialAttr TARGET_CONFIGURATIONS =
      new SpecialAttr("buck.target_configurations", "buck.targetConfigurations");

  // Yep, special attr name does not start with `buck.` and camel case is not camel case
  public static final SpecialAttr FULLY_QUALIFIED_NAME = new SpecialAttr("fully_qualified_name");

  // Yep, camel case is not camel case
  public static final SpecialAttr CELL_PATH = new SpecialAttr("buck.cell_path");

  private final String snakeCase;
  private final String camelCase;

  private SpecialAttr(String snakeCase, String camelCase) {
    this.snakeCase = snakeCase;
    this.camelCase = camelCase;

    // Self-check. We pass both names explicitly for better code search.
    Preconditions.checkArgument(
        CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, camelCase).equals(snakeCase));
  }

  private SpecialAttr(String snakeCase) {
    this.snakeCase = snakeCase;
    this.camelCase = snakeCase;
  }

  @Override
  public String getCamelCase() {
    return camelCase;
  }

  @Override
  public String getSnakeCase() {
    return snakeCase;
  }

  // using identity equals and hashCode

  @Override
  public String toString() {
    return snakeCase;
  }

  @Override
  public int compareTo(SpecialAttr o) {
    return this.snakeCase.compareTo(o.snakeCase);
  }
}

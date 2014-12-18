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

package com.facebook.buck.parser;

import static com.facebook.buck.util.BuckConstant.BUILD_RULES_FILE_NAME;

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Context for parsing build target names. Fully-qualified target names are parsed the same
 * regardless of the context.
 */
@Immutable
public class ParseContext {

  public enum Type {
    BUILD_FILE,
    FULLY_QUALIFIED,

    /**
     * When parsing a build target for the visibility argument in a build file, targets must be
     * fully-qualified, but wildcards are allowed.
     */
    VISIBILITY,
    ;
  }

  private static final ParseContext FULLY_QUALIFIED = new ParseContext(Type.FULLY_QUALIFIED, "");

  private static final ParseContext VISIBILITY = new ParseContext(Type.VISIBILITY, "");

  private final Type type;
  @Nullable
  private final String baseName;

  private ParseContext(Type type, String baseName) {
    this.type = type;
    this.baseName = baseName;
  }

  public Type getType() {
    return type;
  }

  @Nullable
  public String getBaseName() {
    return baseName;
  }

  @Nullable
  public String getBaseNameWithSlash() {
    return BuildTarget.getBaseNameWithSlash(baseName);
  }

  /**
   * Used when parsing target names relative to another target, such as in a build file.
   * @param baseName name such as {@code //first-party/orca}
   */
  public static ParseContext forBaseName(String baseName) {
    Preconditions.checkNotNull(Strings.emptyToNull(baseName));
    return new ParseContext(Type.BUILD_FILE, baseName);
  }

  /**
   * Used when parsing target names in the {@code visibility} argument to a build rule.
   */
  public static ParseContext forVisibilityArgument() {
    return VISIBILITY;
  }

  /**
   * Used when parsing fully-qualified target names only, such as from the command line.
   */
  public static ParseContext fullyQualified() {
    return FULLY_QUALIFIED;
  }

  /**
   * @return description of the target name and context being parsed when an error was encountered.
   *     Examples are ":azzetz in build file //first-party/orca/orcaapp/BUCK" and
   *     "//first-party/orca/orcaapp:mezzenger in context FULLY_QUALIFIED"
   */
  public String makeTargetDescription(String buildTargetName) {
    String location = getType().name();
    if (getType() == ParseContext.Type.BUILD_FILE) {
      return String.format("%s in build file %s%s",
          buildTargetName,
          getBaseNameWithSlash(),
          BUILD_RULES_FILE_NAME);
    } else {
      return String.format("%s in context %s", buildTargetName, location);
    }
  }
}

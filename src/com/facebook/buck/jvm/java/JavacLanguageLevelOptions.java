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

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class JavacLanguageLevelOptions implements AddsToRuleKey {

  /** Enum that represents java version. */
  public enum JavaVersion {
    VERSION_1_1("1.1"),
    VERSION_1_2("1.2"),
    VERSION_1_3("1.3"),
    VERSION_1_4("1.4"),
    VERSION_5("5"),
    VERSION_6("6"),
    VERSION_7("7"),
    VERSION_8("8"),
    VERSION_9("9"),
    VERSION_10("10"),
    VERSION_11("11");

    private final String version;

    JavaVersion(String version) {
      this.version = version;
    }

    public String getVersion() {
      return version;
    }

    private static JavaVersion toJavaLanguageVersion(String version) {
      String versionString = version;
      double versionDouble = Double.parseDouble(version);
      if (versionDouble >= 1.5 && versionDouble <= 1.8) {
        versionString = Integer.toString(((int) (versionDouble * 10)) - 10);
      } else if (versionDouble % 1 == 0) { // if double doesn't have decimal part
        versionString = Integer.toString((int) versionDouble);
      }

      for (JavaVersion javaVersion : values()) {
        if (versionString.equals(javaVersion.getVersion())) {
          return javaVersion;
        }
      }
      throw new IllegalArgumentException("Can't find java version for string: " + version);
    }
  }

  public static final JavacLanguageLevelOptions DEFAULT =
      JavacLanguageLevelOptions.builder().build();

  // Default combined source and target level.
  public static final String TARGETED_JAVA_VERSION = "7";

  @Value.Default
  @AddToRuleKey
  protected String getSourceLevel() {
    return TARGETED_JAVA_VERSION;
  }

  @Value.Default
  @AddToRuleKey
  protected String getTargetLevel() {
    return TARGETED_JAVA_VERSION;
  }

  @Value.Derived
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public JavaVersion getSourceLevelValue() {
    return JavaVersion.toJavaLanguageVersion(getSourceLevel());
  }

  @Value.Derived
  @ExcludeFromRuleKey(
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  public JavaVersion getTargetLevelValue() {
    return JavaVersion.toJavaLanguageVersion(getTargetLevel());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableJavacLanguageLevelOptions.Builder {}
}

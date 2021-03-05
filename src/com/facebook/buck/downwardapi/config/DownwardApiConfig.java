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

package com.facebook.buck.downwardapi.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.environment.Platform;
import org.immutables.value.Value;

/** Downward API specific buck config */
@BuckStyleValue
public abstract class DownwardApiConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "downward_api";

  private static final boolean IS_WINDOWS = Platform.detect() == Platform.WINDOWS;

  private static final boolean IS_ENABLED_BY_DEFAULT = true;

  @Override
  public abstract BuckConfig getDelegate();

  public static DownwardApiConfig of(BuckConfig delegate) {
    return ImmutableDownwardApiConfig.ofImpl(delegate);
  }

  @Value.Lazy
  public boolean isEnabledForCxx() {
    return isEnabled("cxx_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForGenrule() {
    return isEnabled("genrule_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForAndroid() {
    return isEnabled("android_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForApple() {
    return isEnabled("apple_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForJava() {
    return isEnabled("java_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForGroovy() {
    return isEnabled("groovy_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForKotlin() {
    return isEnabled("kotlin_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForScala() {
    return isEnabled("scala_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForInfer() {
    return isEnabled("infer_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForD() {
    return isEnabled("d_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForCSharp() {
    return isEnabled("csharp_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForGo() {
    return isEnabled("go_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForHalide() {
    return isEnabled("halide_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForHaskell() {
    return isEnabled("haskell_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForLua() {
    return isEnabled("lua_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForOCaml() {
    return isEnabled("ocaml_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForRust() {
    return isEnabled("rust_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForPython() {
    return isEnabled("python_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForJs() {
    return isEnabled("js_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForTests() {
    return isEnabled("tests_enabled");
  }

  @Value.Lazy
  public boolean isEnabledForRuleAnalysis() {
    return isEnabled("rule_analysis_enabled");
  }

  private boolean isEnabled(String configKey) {
    if (IS_WINDOWS && !isEnabledForWindows()) {
      return false;
    }
    return getDelegate().getBooleanValue(SECTION, configKey, IS_ENABLED_BY_DEFAULT);
  }

  @Value.Lazy
  public boolean isEnabledForWindows() {
    return getDelegate().getBooleanValue(SECTION, "windows_enabled", IS_ENABLED_BY_DEFAULT);
  }
}

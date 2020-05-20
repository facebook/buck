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
import org.immutables.value.Value;

/** Downward API specific buck config */
@BuckStyleValue
public abstract class DownwardApiConfig implements ConfigView<BuckConfig> {

  private static final String SECTION = "downward_api";

  @Override
  public abstract BuckConfig getDelegate();

  public static DownwardApiConfig of(BuckConfig delegate) {
    return ImmutableDownwardApiConfig.ofImpl(delegate);
  }

  @Value.Lazy
  public boolean isEnabledForCxx() {
    return getDelegate().getBooleanValue(SECTION, "cxx_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForGenrule() {
    return getDelegate().getBooleanValue(SECTION, "genrule_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForAndroid() {
    return getDelegate().getBooleanValue(SECTION, "android_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForApple() {
    return getDelegate().getBooleanValue(SECTION, "apple_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForJava() {
    return getDelegate().getBooleanValue(SECTION, "java_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForGroovy() {
    return getDelegate().getBooleanValue(SECTION, "groovy_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForKotlin() {
    return getDelegate().getBooleanValue(SECTION, "kotlin_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForScala() {
    return getDelegate().getBooleanValue(SECTION, "scala_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForInfer() {
    return getDelegate().getBooleanValue(SECTION, "infer_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForD() {
    return getDelegate().getBooleanValue(SECTION, "d_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForCSharp() {
    return getDelegate().getBooleanValue(SECTION, "csharp_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForGo() {
    return getDelegate().getBooleanValue(SECTION, "go_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForHalide() {
    return getDelegate().getBooleanValue(SECTION, "halide_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForHaskell() {
    return getDelegate().getBooleanValue(SECTION, "haskell_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForLua() {
    return getDelegate().getBooleanValue(SECTION, "lua_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForOCaml() {
    return getDelegate().getBooleanValue(SECTION, "ocaml_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForRust() {
    return getDelegate().getBooleanValue(SECTION, "rust_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForPython() {
    return getDelegate().getBooleanValue(SECTION, "python_enabled", false);
  }

  @Value.Lazy
  public boolean isEnabledForJs() {
    return getDelegate().getBooleanValue(SECTION, "js_enabled", false);
  }
}

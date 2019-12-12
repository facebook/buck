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

package com.facebook.buck.jvm.java.abi;

import java.util.Optional;
import javax.tools.Diagnostic;

public enum AbiGenerationMode {
  /** Generate ABIs by stripping .class files */
  CLASS(Optional.empty()),
  /** Generate ABIs by parsing .java files with dependency ABIs available */
  SOURCE(Optional.empty()),
  /**
   * Output warnings for things that aren't legal when generating ABIs from source without
   * dependency ABIs
   */
  MIGRATING_TO_SOURCE_ONLY(Optional.of(Diagnostic.Kind.WARNING)),
  /**
   * Generate ABIs by parsing .java files without dependency ABIs available (has some limitations)
   */
  SOURCE_ONLY(Optional.of(Diagnostic.Kind.ERROR));

  private final Optional<Diagnostic.Kind> diagnosticKindForSourceOnlyAbiCompatibility;

  AbiGenerationMode(Optional<Diagnostic.Kind> diagnosticKindForSourceOnlyAbiCompatibility) {
    this.diagnosticKindForSourceOnlyAbiCompatibility = diagnosticKindForSourceOnlyAbiCompatibility;
  }

  public boolean checkForSourceOnlyAbiCompatibility() {
    return diagnosticKindForSourceOnlyAbiCompatibility.isPresent();
  }

  public Diagnostic.Kind getDiagnosticKindForSourceOnlyAbiCompatibility() {
    return diagnosticKindForSourceOnlyAbiCompatibility.get();
  }

  public boolean isSourceAbi() {
    return this != CLASS;
  }

  public boolean usesDependencies() {
    return this != SOURCE_ONLY;
  }
}

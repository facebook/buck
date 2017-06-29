/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;

class ResettableDiagnosticCollector<S> implements DiagnosticListener<S> {
  private List<Diagnostic<? extends S>> diagnostics = new ArrayList<>();

  @Override
  public void report(Diagnostic<? extends S> diagnostic) {
    diagnostics.add(diagnostic);
  }

  public List<Diagnostic<? extends S>> getDiagnostics() {
    return Collections.unmodifiableList(diagnostics);
  }

  public void clear() {
    diagnostics = new ArrayList<>();
  }
}

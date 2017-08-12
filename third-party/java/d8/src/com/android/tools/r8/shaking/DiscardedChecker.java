// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringDiagnostic;
import java.util.Set;

public class DiscardedChecker {

  private final Set<DexItem> checkDiscarded;
  private final DexApplication application;
  private boolean fail = false;
  private InternalOptions options;

  public DiscardedChecker(RootSet rootSet, DexApplication application, InternalOptions options) {
    this.checkDiscarded = rootSet.checkDiscarded;
    this.application = application;
    this.options = options;
  }

  public void run() {
    for (DexProgramClass clazz : application.classes()) {
      checkItem(clazz);
      clazz.forEachMethod(this::checkItem);
      clazz.forEachField(this::checkItem);
    }
    if (fail) {
      throw new CompilationError("Discard checks failed.");
    }
  }

  private void checkItem(DexItem item) {
    if (checkDiscarded.contains(item)) {
      options.diagnosticsHandler.info(
          new StringDiagnostic("Item " + item.toSourceString() + " was not discarded."));
      fail = true;
    }
  }
}

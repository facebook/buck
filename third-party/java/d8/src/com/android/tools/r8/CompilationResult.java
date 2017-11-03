// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexApplication;

public class CompilationResult {

  public final OutputSink outputSink;
  public final DexApplication dexApplication;
  public final AppInfo appInfo;

  public CompilationResult(OutputSink outputSink, DexApplication dexApplication, AppInfo appInfo) {
    this.outputSink = outputSink;
    this.dexApplication = dexApplication;
    this.appInfo = appInfo;
  }
}

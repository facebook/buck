// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.OutputMode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

abstract class BaseOutput {

  private final AndroidApp app;
  private final OutputMode outputMode;

  BaseOutput(AndroidApp app, OutputMode outputMode) {
    this.app = app;
    this.outputMode = outputMode;
  }

  // Internal access to the underlying app.
  AndroidApp getAndroidApp() {
    return app;
  }

  // Internal access to the options.
  public OutputMode getOutputMode() {
    return outputMode;
  }

  /**
   * Get the list of compiled DEX resources.
   *
   * <p>The order of the list corresponds to the usual naming convention:
   *
   * <pre>
   *   resources.get(0)     ~=~ classes.dex  (the main dex file)
   *   resources.get(N - 1) ~=~ classesN.dex (where N > 0).
   * </pre>
   *
   * @return an immutable list of compiled DEX resources.
   */
  public List<Resource> getDexResources() {
    return ImmutableList.copyOf(app.getDexProgramResourcesForOutput());
  }

  /**
   * Write the output resources to a zip-archive or directory.
   *
   * @param output Path to an existing directory or a zip-archive.
   */
  public abstract void write(Path output) throws IOException;
}

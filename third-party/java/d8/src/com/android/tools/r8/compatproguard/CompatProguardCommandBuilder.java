// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.compatproguard;

import com.android.tools.r8.R8Command;

public class CompatProguardCommandBuilder extends R8Command.Builder {
  public CompatProguardCommandBuilder(boolean forceProguardCompatibility,
      boolean ignoreMissingClasses) {
    super(true, forceProguardCompatibility, true, ignoreMissingClasses);
    setEnableDesugaring(false);
  }
}

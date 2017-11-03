// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.compatdx;

import com.android.tools.r8.D8Command;

public class CompatDxCommandBuilder extends D8Command.Builder {
  CompatDxCommandBuilder() {
    super(true);
    setEnableDesugaring(false);
  }
}

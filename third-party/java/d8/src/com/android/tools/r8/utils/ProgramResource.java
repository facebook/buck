// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.Resource;

// A program resource can come from either DEX or Java-bytecode source. Currently we only support
// Java-bytecode sources for classpath and library inputs. The occurance of VDEX here is to allow
// toolings to read in VDEX sources (eg, the Marker extraction tool).
public class ProgramResource {
  public enum Kind {
    DEX,
    CLASS,
    VDEX
  }

  public final Kind kind;
  public final Resource resource;

  ProgramResource(Kind kind, Resource resource) {
    this.kind = kind;
    this.resource = resource;
  }
}

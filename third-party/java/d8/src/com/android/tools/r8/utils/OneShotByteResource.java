// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

class OneShotByteResource extends Resource {

  private byte[] bytes;
  private final Set<String> classDescriptors;

  OneShotByteResource(Origin origin, byte[] bytes, Set<String> classDescriptors) {
    super(origin);
    assert bytes != null;
    this.bytes = bytes;
    this.classDescriptors = classDescriptors;
  }

  @Override
  public Set<String> getClassDescriptors() {
    return classDescriptors;
  }

  @Override
  public InputStream getStream() throws IOException {
    assert bytes != null;
    InputStream result = new ByteArrayInputStream(bytes);
    bytes = null;
    return result;
  }
}

/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.log;

import com.facebook.buck.util.zip.BestCompressionGZIPOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.FileHandler;

public class CompressingFileHandler extends FileHandler {

  public CompressingFileHandler() throws IOException, SecurityException {}

  @Override
  protected synchronized void setOutputStream(OutputStream out) throws SecurityException {
    OutputStream stream;
    try {
      stream = new BestCompressionGZIPOutputStream(out, true);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    super.setOutputStream(stream);
  }
}

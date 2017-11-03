// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * A class describing version properties.
 */
public class VersionProperties {

  private static final String VERSION_CODE_KEY = "version-file.version.code";
  private static final String SHA_KEY = "version.sha";
  private static final String RELEASER_KEY = "releaser";

  private static final String RESOURCE_NAME = "r8-version.properties";

  private String codeBase;
  private String releaser;

  public VersionProperties(ClassLoader loader)
      throws IOException {
    try (InputStream resourceStream = loader.getResourceAsStream(RESOURCE_NAME)) {
      if (resourceStream == null) {
        throw new FileNotFoundException(RESOURCE_NAME);
      }
      initWithInputStream(resourceStream);
    }
  }

  private void initWithInputStream(InputStream is) throws IOException {
    Properties prop = new Properties();
    prop.load(is);

    long versionFileVersion = Long.parseLong(prop.getProperty(VERSION_CODE_KEY));
    assert versionFileVersion >= 1;

    codeBase = prop.getProperty(SHA_KEY);
    releaser = prop.getProperty(RELEASER_KEY);
  }

  public String getDescription() {
    if (codeBase != null && !codeBase.trim().isEmpty()) {
      return "build " + codeBase + (releaser != null ? " from " + releaser : "");
    } else {
      return "eng build" + (releaser != null ? " from " + releaser : "");
    }
  }

  @Override
  public String toString() {
    return codeBase + " from " + releaser;
  }
}

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

package com.facebook.buck.android;

import com.android.ddmlib.testrunner.XmlTestRunListener;

import java.io.File;
import java.io.IOException;

public class BuckXmlTestRunListener extends XmlTestRunListener {

  private final String deviceSerialNumber;
  protected static final String TEST_RESULT_FILE_SUFFIX = ".xml";
  protected static final String TEST_RESULT_FILE_PREFIX = "test_result_";

  public BuckXmlTestRunListener(String deviceSerialNumber) {
    super();
    this.deviceSerialNumber = deviceSerialNumber;
  }

  @Override
  /**
   * Creates a {@link File} where the report will be created.  Instead
   * of creating a temp file, create a file with the devices serial
   * number, so it's possible to refer back to it afterwards.
   *
   * @param reportDir the root directory of the report.
   * @return a file
   * @throws IOException
   */
  protected File getResultFile(File reportDir) throws IOException {
    File reportFile = new File(
        reportDir,
        TEST_RESULT_FILE_PREFIX + deviceSerialNumber + TEST_RESULT_FILE_SUFFIX);
    return reportFile;
  }

}

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

package com.facebook.buck.testrunner;

import com.android.ddmlib.testrunner.XmlTestRunListener;

import java.io.File;
import java.io.IOException;

public class BuckXmlTestRunListener extends XmlTestRunListener {

  protected static final String TEST_RESULT_FILE = "test_result.xml";

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
    File reportFile = new File(reportDir, TEST_RESULT_FILE);
    return reportFile;
  }

}

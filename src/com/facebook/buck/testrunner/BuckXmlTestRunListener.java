/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testrunner;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.ddmlib.testrunner.XmlTestRunListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

public class BuckXmlTestRunListener extends XmlTestRunListener {

  protected static final String TEST_RESULT_FILE = "test_result.xml";
  private String mRunFailureMessage = null;
  private File mReportDir;
  private IDevice mDevice;

  BuckXmlTestRunListener(IDevice device) {
    mDevice = device;
  }

  @Override
  public void setReportDir(File file) {
    super.setReportDir(file);
    mReportDir = file;
  }

  @Override
  public void testRunStarted(String runName, int testCount) {
    super.testRunStarted(runName, testCount);
  }

  @Override
  public void testRunFailed(String errorMessage) {
    mRunFailureMessage = errorMessage;
  }

  @Override
  public void testRunEnded(long elapsedTime, Map<String, String> runMetrics) {
    super.testRunEnded(elapsedTime, runMetrics);
    addMainTestResult();
  }

  @Override
  public void testFailed(TestIdentifier test, String trace) {
    if (mDevice != null && trace.endsWith("Check device logcat for details")) {
      try {
        List<String> debugLines = new ArrayList<>();
        IShellOutputReceiver receiver =
            new MultiLineReceiver() {
              private boolean inDebuggerdDump = false;

              @Override
              public boolean isCancelled() {
                return false;
              }

              @Override
              public void processNewLines(String[] lines) {
                // Try to capture the last debuggerd dump in logcat.
                for (String line : lines) {
                  if (!line.contains("DEBUG")) {
                    continue;
                  }
                  if (line.contains("*** *** ***")) {
                    debugLines.clear();
                    inDebuggerdDump = true;
                  }
                  if (inDebuggerdDump) {
                    debugLines.add(line);
                  }
                }
              }
            };

        // Wait a short time for debuggerd to (hopefully) write some info out.
        Thread.sleep(1000);
        mDevice.executeShellCommand("logcat -d", receiver, 10, TimeUnit.SECONDS);

        StringBuilder builder = new StringBuilder();
        builder.append(trace);
        if (debugLines.isEmpty()) {
          builder.append("\nSearched logcat, but unable to find a crash.\n");
        } else {
          builder.append("\nFound a crash in logcat:\n\n");
          for (String line : debugLines) {
            builder.append(line).append('\n');
          }
        }
        trace = builder.toString();
      } catch (Exception e) {
        // Is this too verbose?  Should we just ignore failure here?
        // Logging isn't configured, unfortunately.
        System.err.println("Warning: Failed to collect more info from logcat.");
        e.printStackTrace();
      }
    }
    super.testFailed(test, trace);
  }

  /** Adds one more XML element to the test_result.xml tracking the result of the whole process. */
  private void addMainTestResult() {
    try {
      File resultFile = getResultFile(mReportDir);

      DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
      Document doc = docBuilder.parse(resultFile);

      Node testsuite = doc.getElementsByTagName("testsuite").item(0);
      if (mRunFailureMessage != null) {
        Element failureNode = doc.createElement("failure");
        failureNode.setTextContent(mRunFailureMessage);
        testsuite.appendChild(failureNode);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(resultFile);
        transformer.transform(source, result);
      }

    } catch (IOException | ParserConfigurationException | SAXException | TransformerException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a {@link File} where the report will be created. Instead of creating a temp file,
   * create a file with the devices serial number, so it's possible to refer back to it afterwards.
   *
   * @param reportDir the root directory of the report.
   * @return a file
   * @throws IOException
   */
  @Override
  protected File getResultFile(File reportDir) {
    return new File(reportDir, TEST_RESULT_FILE);
  }
}

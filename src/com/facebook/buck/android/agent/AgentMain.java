/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android.agent;

import com.facebook.buck.android.agent.util.AgentUtil;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main class for an agent that runs on an Android device to aid app installation.
 *
 * <p>This does not run as a normal Android app.  It is packaged into an APK and installed
 * as a convenient way to get it on the device, but it is run from the "adb shell" command line
 * using the "dalvikvm" command.  Therefore, we do not have an Android Context and therefore
 * cannot interact with any system services.
 */
public class AgentMain {

  private AgentMain() {}

  public static final int CONNECT_TIMEOUT_MS = 5000;
  public static final int RECEIVE_TIMEOUT_MS = 20000;

  private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

  public static void main(String args[]) throws IOException {
    if (args.length == 0) {
      LOG.severe("No command specified");
      System.exit(1);
    }

    String command = args[0];
    List<String> userArgs =
        Collections.unmodifiableList(Arrays.asList(args).subList(1, args.length));

    try {
      if (command.equals("get-signature")) {
        doGetSignature(userArgs);
      } else if (command.equals("mkdir-p")) {
        doMkdirP(userArgs);
      } else if (command.equals("receive-file")) {
        doReceiveFile(userArgs);
      } else {
        throw new IllegalArgumentException("Unknown command: " + command);
      }
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Command failed", e);
      System.exit(1);
    }
    System.exit(0);
  }

  /**
   * Print the signature of an APK to stdout.  The APK path is passed as
   * the only command line argument.
   */
  private static void doGetSignature(List<String> userArgs) throws IOException {
    if (userArgs.size() != 1) {
      throw new IllegalArgumentException("usage: get-signature FILE");
    }
    String packagePath = userArgs.get(0);

    System.out.println(AgentUtil.getJarSignature(packagePath));
  }

  /**
   * Roughly equivalent to the shell command "mkdir -p".
   *
   * Note that some (all?) versions of Android will force restrictive permissions
   * on the created directories.
   */
  private static void doMkdirP(List<String> userArgs) throws IOException {
    if (userArgs.size() != 1) {
      throw new IllegalArgumentException("usage: mkdir -p PATH");
    }

    File path = new File(userArgs.get(0));
    boolean success = path.mkdirs();
    if (!success) {
      throw new IOException("Creating directory failed.");
    }
  }

  /**
   * Receive a file over the network and write it to disk.
   *
   * <p>Arguments are
   *   <ol>
   *     <li>The port to listen on.
   *     <li>The size of the file to receive.
   *     <li>The path to write it to.
   *   </ol>
   *
   * <p>At startup, the agent will print a textual secret key to stdout.  It consists of exactly
   * {@link AgentUtil#TEXT_SECRET_KEY_SIZE} bytes.  The caller must prepend those bytes to the
   * file being transmitted, in order to prevent another process from sending a malicious payload.
   */
  private static void doReceiveFile(List<String> userArgs) throws IOException {
    if (userArgs.size() != 3) {
      throw new IllegalArgumentException("usage: receive-file PORT SIZE PATH");
    }

    int port = Integer.parseInt(userArgs.get(0));
    int size = Integer.parseInt(userArgs.get(1));
    File path = new File(userArgs.get(2));

    // First make sure we can bind to the port.
    ServerSocket serverSocket = null;
    try {
      serverSocket = new ServerSocket(port);

      byte[] secretKey = createAndSendSessionKey();

      // Open the connection with appropriate timeouts.
      serverSocket.setSoTimeout(CONNECT_TIMEOUT_MS);
      Socket connectionSocket = serverSocket.accept();
      connectionSocket.setSoTimeout(RECEIVE_TIMEOUT_MS);
      InputStream input = connectionSocket.getInputStream();

      receiveAndValidateSessionKey(secretKey, input);

      doRawReceiveFile(path, size, input);
    } finally {
      if (serverSocket != null) {
        serverSocket.close();
      }
    }
  }

  private static byte[] createAndSendSessionKey() throws IOException {
    // Generate a random key to authenticate the network connection.
    // On some devices, I had trouble using SecureRandom in a non-app context
    // (it failed to find a native library), so just access urandom directly.
    byte[] binaryKey = new byte[AgentUtil.BINARY_SECRET_KEY_SIZE];
    InputStream urandom = new BufferedInputStream(new FileInputStream("/dev/urandom"));
    try {
      int got = urandom.read(binaryKey);
      if (got != binaryKey.length) {
        throw new RuntimeException("Failed to receive sufficient random bytes for key.");
      }
    } finally {
      urandom.close();
    }
    StringBuilder keyBuilder = new StringBuilder();
    for (byte b : binaryKey) {
      keyBuilder.append(String.format((Locale) null, "%02x", b));
    }
    byte[] secretKey = keyBuilder.toString().getBytes();
    if (secretKey.length != AgentUtil.TEXT_SECRET_KEY_SIZE) {
      throw new RuntimeException("Bug in secret key formatting");
    }

    // Send the key over stdout so only the host can read it.
    System.out.write(secretKey);
    System.out.flush();

    return secretKey;
  }

  private static void receiveAndValidateSessionKey(
      byte[] secretKey,
      InputStream clientInput) throws IOException {
    byte[] receivedKey = new byte[secretKey.length];
    int receivedKeySize = clientInput.read(receivedKey);
    if (receivedKeySize != receivedKey.length) {
      throw new IllegalStateException("Did not receive full-length key.");
    }
    if (!Arrays.equals(secretKey, receivedKey)) {
      throw new IllegalStateException("Received incorrect secret key.");
    }
  }

  private static void doRawReceiveFile(File path, int size, InputStream clientInput)
      throws IOException {
    // Create a temp file to receive the payload, so we don't need to worry about
    // partially-received files.  The host takes care of deleting temp files.
    File tempfile = File.createTempFile(
        AgentUtil.TEMP_PREFIX + path.getName() + "-",
        ".tmp",
        path.getParentFile());
    FileOutputStream output = new FileOutputStream(tempfile);

    // Keep track of our starting time so we can enforce a timeout on slow but steady uploads.
    long receiveStartMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    // Keep track of the total received size to verify the payload.
    long totalSize = 0;
    try {
      final int bufferSize = 128 * 1024;
      byte[] buf = new byte[bufferSize];
      while (true) {
        long currentTimeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        if (currentTimeMs - receiveStartMs > RECEIVE_TIMEOUT_MS) {
          throw new RuntimeException("Receive failed to complete before timeout.");
        }
        int got = clientInput.read(buf);
        if (got == -1) {
          break;
        }
        output.write(buf, 0, got);
        totalSize += got;
      }
    } finally {
      output.close();
    }
    if (totalSize != size) {
      throw new RuntimeException("Received only " + totalSize + " of " + size + " bytes.");
    }
    boolean success = tempfile.renameTo(path);
    if (!success) {
      throw new RuntimeException("Failed to rename temp file.");
    }
  }
}

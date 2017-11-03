// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.dex.DexFileReader;
import com.android.tools.r8.dex.Segment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

public class BSPatch {

  private final static char[] BSDIFF_MAGIC = "BSDIFF40".toCharArray();
  private final static int BSDIFF_HEADER_LENGTH = 32;

  private final ByteBuffer patchInput;
  private final ByteBuffer oldInput;
  private final Path output;
  private final Path dexPath;

  private InputStream controlStream;
  private InputStream diffStream;
  private InputStream extraStream;

  private long controlBytesRead;
  private long diffBytesRead;
  private long extraBytesRead;
  private int controlBlockLen;
  private int diffBlockLen;
  private int extraBlockLen;

  public static void main(String[] args) {
    boolean imageMode = args.length > 1 && args[0].equals("-i");
    int argOffset = imageMode ? 1 : 0;
    if (args.length < argOffset + 3) {
      System.out.println("Usage: [-i] <patch file> <original dex> <output file> [target dex]");
      System.exit(1);
    }
    try {
      new BSPatch(Paths.get(args[argOffset]), Paths.get(args[argOffset + 1]),
          Paths.get(args[argOffset + 2]),
          args.length != argOffset + 4 ? null : Paths.get(args[argOffset + 3]))
          .apply(imageMode);
    } catch (IOException e) {
      System.err.println("File I/O error: " + e.toString());
    } catch (CompressorException e) {
      System.err.println("BZIP error: " + e.toString());
    }
  }

  private BSPatch(Path patchInput, Path oldInput, Path output, Path dexPath) throws IOException {
    this.patchInput = ByteBuffer.wrap(Files.readAllBytes(patchInput));
    this.oldInput = ByteBuffer.wrap(Files.readAllBytes(oldInput));
    this.output = output;
    this.dexPath = dexPath;
  }

  public void apply(boolean imageMode) throws CompressorException, IOException {
    PatchExecutor executor = imageMode ? new ImageExecutor(dexPath) : new FileExecutor();
    checkHeader();
    setupSegmentsAndOutput(executor);
    processControl(executor);
    executor.writeResult();
    printStats();
  }

  private int percentOf(long a, long b) {
    return (int) ((((double) a) / ((double) b)) * 100);
  }

  private void printStats() {
    System.out.println("Size of control block (compressed bytes): " + controlBlockLen);
    System.out.println("Size of control block (read bytes): " + controlBytesRead);
    System.out
        .println("Compression of control block: " + percentOf(controlBlockLen, controlBytesRead));
    System.out.println("Size of diff data block (compressed bytes): " + diffBlockLen);
    System.out.println("Size of diff data block (read bytes): " + diffBytesRead);
    System.out
        .println("Compression of diff data block: " + percentOf(diffBlockLen, diffBytesRead));
    System.out.println("Size of extra data block (compressed bytes): " + extraBlockLen);
    System.out.println("Size of extra data block (read bytes): " + extraBytesRead);
    System.out
        .println("Compression of extra data block: " + percentOf(extraBlockLen, extraBytesRead));
  }

  private void processControl(PatchExecutor executor) throws IOException {
    int blockSize;
    while ((blockSize = readNextControlEntry()) != Integer.MIN_VALUE) {
      int extraSize = readNextControlEntry();
      int advanceOld = readNextControlEntry();
      executor.copyDiff(blockSize);
      executor.copyOld(blockSize);
      executor.submitBlock(blockSize);
      executor.copyExtra(extraSize);
      executor.skipOld(advanceOld);
    }
  }

  private void checkHeader() {
    for (int i = 0; i < BSDIFF_MAGIC.length; i++) {
      if (patchInput.get() != BSDIFF_MAGIC[i]) {
        throw new RuntimeException("Illegal patch, wrong magic!");
      }
    }
  }

  private void setupSegmentsAndOutput(PatchExecutor executor)
      throws CompressorException, IOException {
    controlBlockLen = readOffset();
    diffBlockLen = readOffset();
    int newFileSize = readOffset();

    extraBlockLen =
        patchInput.array().length - (BSDIFF_HEADER_LENGTH + controlBlockLen + diffBlockLen);

    executor.createOutput(newFileSize);
    controlStream = new BZip2CompressorInputStream(
        new ByteArrayInputStream(patchInput.array(), BSDIFF_HEADER_LENGTH, controlBlockLen));
    diffStream = new BZip2CompressorInputStream(
        new ByteArrayInputStream(patchInput.array(), BSDIFF_HEADER_LENGTH + controlBlockLen,
            diffBlockLen));
    extraStream = new BZip2CompressorInputStream(
        new ByteArrayInputStream(patchInput.array(),
            BSDIFF_HEADER_LENGTH + controlBlockLen + diffBlockLen,
            extraBlockLen));
  }

  private int readOffset() {
    byte[] buffer = new byte[8];
    patchInput.get(buffer);
    return decodeOffset(buffer);
  }

  private int readNextControlEntry() throws IOException {
    byte[] buffer = new byte[8];
    int read = controlStream.read(buffer);
    if (read == -1) {
      return Integer.MIN_VALUE;
    }
    controlBytesRead += read;
    assert read == buffer.length;
    return decodeOffset(buffer);
  }

  private static int decodeOffset(byte[] buffer) {
    long offset = buffer[7] & 0x7F;
    for (int i = 6; i >= 0; i--) {
      offset = (offset << 8) | (((int) buffer[i]) & 0xff);
    }
    if ((buffer[7] & 0x80) != 0) {
      offset = -offset;
    }
    assert offset < Integer.MAX_VALUE && offset > Integer.MIN_VALUE;
    return (int) offset;
  }

  private static abstract class PatchExecutor {

    public abstract void createOutput(int newFileSize);

    public abstract void copyDiff(int blockSize) throws IOException;

    public abstract void copyOld(int blockSize) throws IOException;

    public abstract void submitBlock(int blockSize);

    public abstract void copyExtra(int extraSize) throws IOException;

    public abstract void skipOld(int advanceOld) throws IOException;

    public abstract void writeResult() throws IOException;
  }

  private class FileExecutor extends PatchExecutor {

    private ByteBuffer resultBuffer;
    private byte[] mergeBuffer = null;

    @Override
    public void createOutput(int newFileSize) {
      resultBuffer = ByteBuffer.allocate(newFileSize);
    }

    @Override
    public void copyDiff(int blockSize) throws IOException {
      assert mergeBuffer == null;
      mergeBuffer = new byte[blockSize];
      int read = diffStream.read(mergeBuffer);
      diffBytesRead += read;
      assert read == blockSize;
    }

    @Override
    public void copyOld(int blockSize) throws IOException {
      assert mergeBuffer.length == blockSize;
      byte[] data = new byte[blockSize];
      oldInput.get(data);
      for (int i = 0; i < mergeBuffer.length; i++) {
        mergeBuffer[i] = (byte) ((((int) mergeBuffer[i]) & 0xff) + (((int) data[i]) & 0xff));
      }
    }

    @Override
    public void submitBlock(int blockSize) {
      assert mergeBuffer != null;
      assert mergeBuffer.length == blockSize;
      resultBuffer.put(mergeBuffer);
      mergeBuffer = null;
    }

    @Override
    public void copyExtra(int extraSize) throws IOException {
      byte[] data = new byte[extraSize];
      int read = extraStream.read(data);
      assert read == extraSize;
      extraBytesRead += read;
      resultBuffer.put(data);
    }

    @Override
    public void skipOld(int delta) throws IOException {
      oldInput.position(oldInput.position() + delta);
    }

    @Override
    public void writeResult() throws IOException {
      OutputStream outputStream = Files.newOutputStream(output);
      outputStream.write(resultBuffer.array());
      outputStream.close();
    }
  }

  private class ImageExecutor extends PatchExecutor {

    private final Path dexPath;

    BufferedImage image;
    int position = 0;
    int width;
    int height;

    private ImageExecutor(Path dexPath) {
      this.dexPath = dexPath;
    }

    @Override
    public void createOutput(int newFileSize) {
      int root = (int) Math.sqrt(newFileSize);
      width = newFileSize / root;
      height = newFileSize / width + 1;
      image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    }

    @Override
    public void copyDiff(int blockSize) throws IOException {
      byte[] buffer = new byte[blockSize];
      int read = diffStream.read(buffer);
      assert read == blockSize;
      diffBytesRead += read;
      for (int i = 0; i < buffer.length; i++) {
        if (buffer[i] != 0) {
          int y = (position + i) / width;
          int x = (position + i) % width;
          int rgb = image.getRGB(x, y);
          rgb = rgb | 0xFF0000;
          image.setRGB(x, y, rgb);
        }
      }
    }

    @Override
    public void copyOld(int blockSize) throws IOException {
      for (int i = 0; i < blockSize; i++) {
        int y = (position + i) / width;
        int x = (position + i) % width;
        int rgb = image.getRGB(x, y);
        if ((rgb & 0xFF0000) == 0) {
          rgb = rgb | 0xFF00;
        }
        image.setRGB(x, y, rgb);
      }
    }

    @Override
    public void submitBlock(int blockSize) {
      position += blockSize;
    }

    @Override
    public void copyExtra(int extraSize) throws IOException {
      long skipped = extraStream.skip(extraSize);
      assert skipped == extraSize;
      extraBytesRead += skipped;
      for (int i = 0; i < extraSize; i++) {
        int y = (position + i) / width;
        int x = (position + i) % width;
        int rgb = image.getRGB(x, y);
        rgb = rgb | 0xFF;
        image.setRGB(x, y, rgb);
      }
      position += extraSize;
    }

    @Override
    public void skipOld(int advanceOld) throws IOException {
    }

    @Override
    public void writeResult() throws IOException {
      if (dexPath != null) {
        Segment[] segments = DexFileReader.parseMapFrom(dexPath);
        for (Segment segment : segments) {
          int y = segment.offset / width;
          for (int x = 0; x < width; x++) {
            int val = (x / 10) % 2 == 0 ? 0 : 0xffffff;
            image.setRGB(x, y, val);
          }
          System.out.println(segment);
        }
      }
      ImageIO.write(image, "png", output.toFile());
    }
  }
}

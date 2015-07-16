package com.android.dx.merge;

import com.android.dex.Dex;
import com.android.dex.DexIndexOverflowException;

import java.io.File;

/**
 * This test tries to merge given dex files at random, 2 by 2.
 */
public class MergeTest {

  private static final int NUMBER_OF_TRIES = 1000;

  public static void main(String[] args) throws Throwable {

    for (int i = 0; i < NUMBER_OF_TRIES; i++) {
      String fileName1 = args[(int) (Math.random() * args.length)];
      String fileName2 = args[(int) (Math.random() * args.length)];
      try {
        Dex toMerge = new Dex(new File(fileName1));
        Dex toMerge2 = new Dex(new File(fileName2));
        new DexMerger(toMerge, toMerge2, CollisionPolicy.KEEP_FIRST).merge();
      } catch (DexIndexOverflowException e) {
        // ignore index overflow
      } catch (Throwable t) {
        System.err.println(
            "Problem merging those 2 dexes: \"" + fileName1 + "\" and \"" + fileName2 + "\"");
        throw t;
      }
    }
  }
}

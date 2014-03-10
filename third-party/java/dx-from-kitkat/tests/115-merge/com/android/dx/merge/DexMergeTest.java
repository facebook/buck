/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dx.merge;

import com.android.dex.Dex;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import junit.framework.TestCase;

/**
 * Test that DexMerge works by merging dex files, and then loading them into
 * the current VM.
 */
public final class DexMergeTest extends TestCase {

    public void testFillArrayData() throws Exception {
        ClassLoader loader = mergeAndLoad(
                "/testdata/Basic.dex",
                "/testdata/FillArrayData.dex");

        Class<?> basic = loader.loadClass("testdata.Basic");
        assertEquals(1, basic.getDeclaredMethods().length);

        Class<?> fillArrayData = loader.loadClass("testdata.FillArrayData");
        assertTrue(Arrays.equals(
                new byte[] { 0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, -112, -23, 121 },
                (byte[]) fillArrayData.getMethod("newByteArray").invoke(null)));
        assertTrue(Arrays.equals(
                new char[] { 0xFFFF, 0x4321, 0xABCD, 0, 'a', 'b', 'c' },
                (char[]) fillArrayData.getMethod("newCharArray").invoke(null)));
        assertTrue(Arrays.equals(
                new long[] { 4660046610375530309L, 7540113804746346429L, -6246583658587674878L },
                (long[]) fillArrayData.getMethod("newLongArray").invoke(null)));
    }

    public void testTryCatchFinally() throws Exception {
        ClassLoader loader = mergeAndLoad(
                "/testdata/Basic.dex",
                "/testdata/TryCatchFinally.dex");

        Class<?> basic = loader.loadClass("testdata.Basic");
        assertEquals(1, basic.getDeclaredMethods().length);

        Class<?> tryCatchFinally = loader.loadClass("testdata.TryCatchFinally");
        tryCatchFinally.getDeclaredMethod("method").invoke(null);
    }

    public void testStaticValues() throws Exception {
        ClassLoader loader = mergeAndLoad(
                "/testdata/Basic.dex",
                "/testdata/StaticValues.dex");

        Class<?> basic = loader.loadClass("testdata.Basic");
        assertEquals(1, basic.getDeclaredMethods().length);

        Class<?> staticValues = loader.loadClass("testdata.StaticValues");
        assertEquals((byte) 1, staticValues.getField("a").get(null));
        assertEquals((short) 2, staticValues.getField("b").get(null));
        assertEquals('C', staticValues.getField("c").get(null));
        assertEquals(0xabcd1234, staticValues.getField("d").get(null));
        assertEquals(4660046610375530309L,staticValues.getField("e").get(null));
        assertEquals(0.5f, staticValues.getField("f").get(null));
        assertEquals(-0.25, staticValues.getField("g").get(null));
        assertEquals("this is a String", staticValues.getField("h").get(null));
        assertEquals(String.class, staticValues.getField("i").get(null));
        assertEquals("[0, 1]", Arrays.toString((int[]) staticValues.getField("j").get(null)));
        assertEquals(null, staticValues.getField("k").get(null));
        assertEquals(true, staticValues.getField("l").get(null));
        assertEquals(false, staticValues.getField("m").get(null));
    }

    public void testAnnotations() throws Exception {
        ClassLoader loader = mergeAndLoad(
                "/testdata/Basic.dex",
                "/testdata/Annotated.dex");

        Class<?> basic = loader.loadClass("testdata.Basic");
        assertEquals(1, basic.getDeclaredMethods().length);

        Class<?> annotated = loader.loadClass("testdata.Annotated");
        Method method = annotated.getMethod("method", String.class, String.class);
        Field field = annotated.getField("field");

        @SuppressWarnings("unchecked")
        Class<? extends Annotation> marker
                = (Class<? extends Annotation>) loader.loadClass("testdata.Annotated$Marker");

        assertEquals("@testdata.Annotated$Marker(a=on class, b=[A, B, C], "
                + "c=@testdata.Annotated$Nested(e=E1, f=1695938256, g=7264081114510713000), "
                + "d=[@testdata.Annotated$Nested(e=E2, f=1695938256, g=7264081114510713000)])",
                annotated.getAnnotation(marker).toString());
        assertEquals("@testdata.Annotated$Marker(a=on method, b=[], "
                + "c=@testdata.Annotated$Nested(e=, f=0, g=0), d=[])",
                method.getAnnotation(marker).toString());
        assertEquals("@testdata.Annotated$Marker(a=on field, b=[], "
                + "c=@testdata.Annotated$Nested(e=, f=0, g=0), d=[])",
                field.getAnnotation(marker).toString());
        assertEquals("@testdata.Annotated$Marker(a=on parameter, b=[], "
                + "c=@testdata.Annotated$Nested(e=, f=0, g=0), d=[])",
                method.getParameterAnnotations()[1][0].toString());
    }

    /**
     * Merging dex files uses pessimistic sizes that naturally leave gaps in the
     * output files. If those gaps grow too large, the merger is supposed to
     * compact the result. This exercises that by repeatedly merging a dex with
     * itself.
     */
    public void testMergedOutputSizeIsBounded() throws Exception {
        /*
         * At the time this test was written, the output would grow ~25% with
         * each merge. Setting a low 1KiB ceiling on the maximum size caused
         * the file to be compacted every four merges.
         */
        int steps = 100;
        int compactWasteThreshold = 1024;

        Dex dexA = resourceToDexBuffer("/testdata/Basic.dex");
        Dex dexB = resourceToDexBuffer("/testdata/TryCatchFinally.dex");
        Dex merged = new DexMerger(dexA, dexB, CollisionPolicy.KEEP_FIRST).merge();

        int maxLength = 0;
        for (int i = 0; i < steps; i++) {
            DexMerger dexMerger = new DexMerger(dexA, merged, CollisionPolicy.KEEP_FIRST);
            dexMerger.setCompactWasteThreshold(compactWasteThreshold);
            merged = dexMerger.merge();
            maxLength = Math.max(maxLength, merged.getLength());
        }

        int maxExpectedLength = dexA.getLength() + dexB.getLength() + compactWasteThreshold;
        assertTrue(maxLength + " < " + maxExpectedLength, maxLength < maxExpectedLength);
    }

    public ClassLoader mergeAndLoad(String dexAResource, String dexBResource) throws Exception {
        Dex dexA = resourceToDexBuffer(dexAResource);
        Dex dexB = resourceToDexBuffer(dexBResource);
        Dex merged = new DexMerger(dexA, dexB, CollisionPolicy.KEEP_FIRST).merge();
        File mergedDex = File.createTempFile("DexMergeTest", ".classes.dex");
        merged.writeTo(mergedDex);
        File mergedJar = dexToJar(mergedDex);
        // simplify the javac classpath by not depending directly on 'dalvik.system' classes
        return (ClassLoader) Class.forName("dalvik.system.PathClassLoader")
                .getConstructor(String.class, ClassLoader.class)
                .newInstance(mergedJar.getPath(), getClass().getClassLoader());
    }

    private Dex resourceToDexBuffer(String resource) throws IOException {
        return new Dex(getClass().getResourceAsStream(resource));
    }

    private File dexToJar(File dex) throws IOException {
        File result = File.createTempFile("DexMergeTest", ".jar");
        result.deleteOnExit();
        JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(result));
        jarOut.putNextEntry(new JarEntry("classes.dex"));
        copy(new FileInputStream(dex), jarOut);
        jarOut.closeEntry();
        jarOut.close();
        return result;
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
        in.close();
    }
}

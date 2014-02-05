/*
 * Copyright (C) 2007 The Android Open Source Project
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

package java.lang;

public class Object {
    public Object() {
        // This space intentionally left blank.
    }

    public boolean equals(Object o) {
        return true;
    }

    protected void finalize() {
        // This space intentionally left blank.
    }

    public final native Class<? extends Object> getClass();
    public native int hashCode();
    public final native void notify();
    public final native void notifyAll();

    public String toString() {
        return "blort";
    }

    public final void wait() {
        wait(0, 0);
    }

    public final void wait(long time) {
        wait(time, 0);
    }

    public final native void wait(long time, int frac);
}

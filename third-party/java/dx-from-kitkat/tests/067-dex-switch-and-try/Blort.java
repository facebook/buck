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

public class Blort
{
    static public void blort() {
        // This space intentionally left blank.
    }

    // This test has a try-catch but the try code can't possibly throw.
    public int test1(int x) {
        try {
            switch (x) {
                case 1: {
                    x = 10;
                    break;
                }
                case 2: {
                    x = 20;
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // Ignore it.
        }

        return x;
    }

    // This test has a try-catch where the try code can theoretically throw.
    public int test2(int x) {
        try {
            switch (x) {
                case 1: {
                    x = 10;
                    blort();
                    break;
                }
                case 2: {
                    x = 20;
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // Ignore it.
        }

        return x;
    }

    // This test has a switch with a case that has a try-catch where
    // the try code can theoretically throw, but it would be caught
    // inside the case itself.
    public int test3(int x) {
        switch (x) {
            case 1: {
                try {
                    x = 10;
                    blort();
                } catch (RuntimeException ex) {
                    // Ignore it.
                }
                break;
            }
            case 2: {
                x = 20;
                break;
            }
        }

        return x;
    }

    // This test has a try-catch that has a switch with a case that
    // has a try-catch where the try code can theoretically throw, but
    // it would be caught inside the case itself, so the outer
    // exception handler should be considered dead.
    public int test4(int x) {
        try {
            switch (x) {
                case 1: {
                    try {
                        x = 10;
                        blort();
                    } catch (RuntimeException ex) {
                        // Ignore it.
                    }
                    break;
                }
                case 2: {
                    x = 20;
                    break;
                }
            }
        } catch (RuntimeException ex) {
            return 4;
        }

        return x;
    }
}

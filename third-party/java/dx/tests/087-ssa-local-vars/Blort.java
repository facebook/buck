import java.io.IOException;
class Blort {
    private static void arrayCopyTest(int k) {
        // A local variable assigned from an argument
        int j = k;
        // These two locals are defined once and used multiple times
        String[] stringArray = new String[8];
        Object[] objectArray = new Object[8];
        // Should cause another move to be inserted
        Object anotherOne = objectArray;

        if (anotherOne != null) {
            System.out.println("foo");
        }

        // "i" is used in a loop
        for (int i = 0; i < stringArray.length; i++)
            stringArray[i] = new String(Integer.toString(i));

        System.out.println("string -> object");
        System.arraycopy(stringArray, 0, objectArray, 0, stringArray.length);
        System.out.println("object -> string");
        System.arraycopy(objectArray, 0, stringArray, 0, stringArray.length);
        System.out.println("object -> string (modified)");
        objectArray[4] = new Object();
        try {
            System.arraycopy(objectArray, 0, stringArray, 0,stringArray.length);
        } catch (ArrayStoreException ase) {
            // "ase" is an unused local which still must be preserved
            System.out.println("caught ArrayStoreException (expected)");
        }
    }

    private void testConstructor() {
        Blort foo = null;
        try {
            foo = new Blort();
        } catch (Exception ex) {
        }
        System.err.println(foo);
    }
    /**
     * Stolen from
     * java/android/org/apache/http/impl/io/AbstractMessageParser.java
     * Simplified.
     *
     * Checks to see that local variable assignment is preserved through
     * phi's. The key component here is the assignment of previous = current.
     */
    public static void parseHeaderGroup(
            final Object headGroup,
            final Object inbuffer,
            int maxHeaderCount,
            int maxLineLen)
        throws  IOException {

        StringBuilder current = null;
        StringBuilder previous = null;
        for (;;) {
            if (current == null) {
                current = new StringBuilder(64);
            } else {
                current.length();
            }
            int l = inbuffer.hashCode();
            if (l == -1 || current.length() < 1) {
                break;
            }

            if ((current.charAt(0) == ' ' || current.charAt(0) == '\t') && previous != null) {
                int i = 0;
                while (i < current.length()) {
                    char ch = current.charAt(i);
                    if (ch != ' ' && ch != '\t') {
                        break;
                    }
                    i++;
                }
                if (maxLineLen > 0
                        && previous.length() + 1 + current.length() - i > maxLineLen) {
                    throw new IOException("Maximum line length limit exceeded");
                }
                previous.append(' ');
                previous.append(current, i, current.length() - i);
            } else {
                previous = current;
                current = null;
            }
            if (maxHeaderCount > 0) {
                throw new IOException("Maximum header count exceeded");
            }
        }
    }
}

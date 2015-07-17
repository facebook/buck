
class Blort {
    /** Class constructors for enums use a lot of const's */
    enum Foo {
        ONE,TWO,THREE,FOUR,FIVE,SIX,SEVEN,EIGHT
    }

    /** all uses of 10 should be combined except the local assignment */
    void testNumeric() {
        int foo = 10;

        for (int i = 0; i < 10; i++){
            foo += i * 10;
        }

        for (int i = 0; i < 10; i++){
            foo += i + 10;
        }
    }

    void testStrings() {
        StringBuilder sb = new StringBuilder();

        sb.append("foo");
        sb.append("foo");
        sb.append("foo");
        sb.append("foo");
        sb.append("foo");
        sb.append("foo");
    }

    void testCaughtStrings() {
        StringBuilder sb = new StringBuilder();

        sb.append("foo");
        sb.append("foo");
        sb.append("foo");
        try {
            sb.append("foo");
            sb.append("foo");
            sb.append("foo");
        } catch (Throwable tr) {
            System.out.println("foo");
        }
    }

    /** local variables cannot be intermingled */
    void testLocalVars() {
        int i = 10;
        int j = 10;
        int k = 10;
        int a = 10;
        int b = 10;
        int c = 10;

        i *= 10;
    }

    void testNull(Object a) {
        a.equals(null);
        a.equals(null);

    }
}

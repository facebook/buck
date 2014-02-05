package testdata;

public class TryCatchFinally {

    public static void method() {
        int count = 0;
        try {
            if (true) {
                throw new NullPointerException();
            }
            throw new AssertionError();
        } catch (IllegalStateException e) {
            throw new AssertionError();
        } catch (NullPointerException expected) {
            count++;
        } catch (RuntimeException e) {
            throw new AssertionError();
        } finally {
            count++;
        }

        if (count != 2) {
            throw new AssertionError();
        }
    }
}

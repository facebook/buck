
class Blort {

    void testMultipleIdenticalSuccessors(int foo) {
        switch(foo) {
            case 1:
            case 2:
            case 3:
                System.out.println("foo");
            break;
        }
    }

    void testNoPrimarySuccessor() {
        try {
            throw new RuntimeException();
        } catch (RuntimeException ex){
        }
    }
}

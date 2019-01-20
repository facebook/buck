class JavacMain {
  public static void main(String[] args) {
    for (String s : args) {
      System.err.println(s);
    }
    System.exit(0);
  }
}

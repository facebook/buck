public import core.thread;

unittest {
  for( ; ; ) {
    Thread.sleep( dur!("seconds")( 5 ) );
  }
}

void main() {
}

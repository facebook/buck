package buck

case class Class1(val hello: String) {
  def sayHello = {
    Console.println("Hello " + hello)
  }
}

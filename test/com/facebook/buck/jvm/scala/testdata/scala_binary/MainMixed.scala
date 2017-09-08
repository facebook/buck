package buck

object MainMixed extends App {
  val argString = args map { _.toUpperCase } mkString ","
  new Class2(argString).sayHello()
}

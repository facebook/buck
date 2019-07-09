package buck

import scala.language.postfixOps

object Main extends App {
  val argString = args map { _.toUpperCase } mkString ","
  Class1(argString) sayHello
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace ConsoleApp1
{
    public class Program
    {
        static void Main(string[] args)
        {
            Lib1 lib1 = new Lib1(args[0]);
            Lib2 lib2 = new Lib2(args[0]);
            Console.WriteLine(lib1.format(args[1]));
            Console.WriteLine(lib2.format(args[1]));
        }
    }
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace ConsoleApp1
{
    public class Lib1
    {
        private string formatString;

        public Lib1(String formatString)
        {
            this.formatString = formatString;
        }

        public String format(String replacement)
        {
            return String.Format("Lib1.format: {0}", String.Format(this.formatString, replacement));
        }
    }
}

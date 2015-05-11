nailgun
-------

Nailgun is a client, protocol, and server for running Java programs from
the command line without incurring the JVM startup overhead.

Programs run in the server (which is implemented in Java), and are 
triggered by the client (written in C), which handles all I/O.

The server and examples are built using maven.  From the project directory,
"mvn clean install" will do it.

The client is built using make.  From the project directory, 
"make && sudo make install" will do it.  To create the windows client
you will additionally need to "make ng.exe".

For more information, see [the nailgun website](http://martiansoftware.com/nailgun/).

Buck currently uses https://github.com/martylamb/nailgun at
aeb1712ff16f7f532e7790008359f28aba05c627

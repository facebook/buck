
'cp', 'classpath':
	optional, mergeable, values: 1,
	'<list of directories and zip/jar files>',
    excludes { 'jar' },
	"load/coverage classpath";

'jar':
	optional, values: 0,
    excludes { 'cp' },
	"run an executable jar file";

'f', 'fullmetadata':
	optional, values: 0,
	"consider the entire classpath for coverage {including classes that are never loaded}";

'ix', 'filter':
	optional, mergeable, values: 1,
	'<class name wildcard patterns>',
	"coverage inclusion/exclusion patterns {?,*}";

'r', 'report':
	optional, mergeable, values: 1,
	'<list of {txt|html|xml}>',
	"coverage report type list";

'sp', 'sourcepath':
	optional, mergeable, values: 1,
	'<list of source directories>',
	"Java source path for generating reports";

'raw', 'sessiondata':
	optional, values: 0,
	"dump raw session data file";

'out', 'outfile':
	optional, values: 1,
	'<file>',
    requires { 'raw' },
	"raw session data output file (defaults to 'coverage.es')";

'merge':
	optional, values: 1,
    '<y[es]|n[o]>',
    requires { 'raw' },
	"merge session data into output file, if it exists";

'p', 'props', 'properties':
	optional, values: 1,
	'<properties file>',
	"properties override file";

'D':
	optional, mergeable, detailedonly, pattern, values: 1,
	'<value>',
	"generic property override";

'exit':
	optional, detailedonly, values: 0,
	"use System.exit() on termination";

'verbose':
	optional, detailedonly, values: 0,
	excludes {'silent', 'quiet', 'debug'},
	"verbose output operation";

'quiet':
	optional, detailedonly, values: 0,
	excludes {'silent', 'verbose', 'debug'},
	"quiet operation (ignore all but warnings and severe errors)";

'silent':
	optional, detailedonly, values: 0,
	excludes {'quiet', 'verbose', 'debug'},
	"extra-quiet operation (ignore all but severe errors)";

'debug', 'loglevel': 
	optional, detailedonly, values: ?,
	'[<debug trace level>]',
	excludes {'verbose', 'quiet', 'silent'},
	"debug tracing level";

'debugcls':
	optional, detailedonly, values: 1,
	'<debug trace class mask>',
	"class mask for debug tracing";



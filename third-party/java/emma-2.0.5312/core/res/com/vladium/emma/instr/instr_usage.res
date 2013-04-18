
'ip', 'cp', 'instrpath':
	required, mergeable, values: 1,
	'<class directories and zip/jar files>',
	"instrumentation path";

'd', 'dir', 'outdir':
	optional, values: 1,
	'<directory>',
	"instrumentation output directory (required for non-overwrite output modes)";

'out', 'outfile':
	optional, values: 1,
	'<file>',
	"metadata output file (defaults to 'coverage.em')";

'merge':
	optional, values: 1,
    '(y[es]|n[o])',
	"merge metadata into output file, if it exists";

'm', 'outmode':
	optional, values: 1,
	'(copy|overwrite|fullcopy)',
	"output mode (defaults to 'copy')";

'ix', 'filter':
	optional, mergeable, values: 1,
	'<class name wildcard patterns>',
	"coverage inclusion/exclusion patterns {?,*}";

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



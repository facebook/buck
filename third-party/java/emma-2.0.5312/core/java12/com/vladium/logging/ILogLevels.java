/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ILogLevels.java,v 1.1.1.1.2.1 2004/07/16 23:32:29 vlad_r Exp $
 */
package com.vladium.logging;

// ----------------------------------------------------------------------------
/**
 * An enumeration of log level values used in conjunction with the API in
 * {@link Logger} 
 * 
 * @see Logger
 * 
 * @author Vlad Roubtsov, (C) 2001
 */
public
interface ILogLevels
{
    // public: ................................................................
    
    // note: must start with 0
    
    /** log level excluding all but severe errors */
    int SEVERE          = 0;    // "-silent"
    /** log level for quieter than normal operation */
    int WARNING         = 1;    // "-quiet"
    /** default log level */
    int INFO            = 2;    // default
    /** log level for chattier than normal operation */
    int VERBOSE         = 3;    // "-verbose"
    
    // debug levels:
    
    /** debug trace log level */
    int TRACE1          = 4;
    /** finer debug trace log level */
    int TRACE2          = 5;
    /** finest debug trace log level */
    int TRACE3          = 6;
    
    // special constants:
    
    /** setting log level to NONE disables all logging */
    int NONE            = -1;
    /** setting log level to ALL enables all log levels */
    int ALL             = TRACE3 + 1;
    

    // human readable strings: 
    
    String SEVERE_STRING    = "severe";
    String SILENT_STRING    = "silent";
    String WARNING_STRING   = "warning";
    String QUIET_STRING     = "quiet";
    String INFO_STRING      = "info";
    String VERBOSE_STRING   = "verbose";
    String TRACE1_STRING    = "trace1";
    String TRACE2_STRING    = "trace2";
    String TRACE3_STRING    = "trace3";
    
    String NONE_STRING      = "none";
    String ALL_STRING       = "all";

} // end of class
// ----------------------------------------------------------------------------

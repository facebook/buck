/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IReportProperties.java,v 1.1.1.1 2004/05/09 16:57:37 vlad_r Exp $
 */
package com.vladium.emma.report;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IReportProperties
{
    // public: ................................................................
   
    // TODO: separate props for diff kinds of files (m, c, reports) ?
    
    String PREFIX = "report.";
    
    // parameter:
    String OUT_ENCODING     = "out.encoding";
    String OUT_DIR          = "out.dir";
    String OUT_FILE         = "out.file";

    // parameter:
    String UNITS_TYPE       = "units";
    // values:
    String COUNT_UNITS      = "count";
    String INSTR_UNITS      = "instr";
        
    // parameter:
    String VIEW_TYPE        = "view";
    // values:
    String CLS_VIEW         = "class";
    String SRC_VIEW         = "source";

    // parameter:
    String HIDE_CLASSES     = "hideclasses"; // boolean
    
    // parameter:
    String DEPTH            = "depth";
    // values:
    String DEPTH_ALL        = "all";
    String DEPTH_PACKAGE    = "package";
    String DEPTH_SRCFILE    = "source";
    String DEPTH_CLASS      = "class";
    String DEPTH_METHOD     = "method";
    
    // parameter:
    String COLUMNS          = "columns"; // comma-separated list
    // values:
    String ITEM_NAME_COLUMN         = "name";
    String CLASS_COVERAGE_COLUMN    = "class";
    String METHOD_COVERAGE_COLUMN   = "method";
    String BLOCK_COVERAGE_COLUMN    = "block";
    String LINE_COVERAGE_COLUMN     = "line";
    
    // parameter:
    String SORT             = "sort"; // comma-separated list of ('+'/'-'-prefixed column names)
    char ASC                = '+'; // default
    char DESC               = '-';
    
    // parameter:
    String METRICS          = "metrics"; // comma-separated list of (column name:metric) pairs
    char MSEPARATOR         = ':';

    // defaults:
    
    String DEFAULT_UNITS_TYPE = INSTR_UNITS;
    String DEFAULT_VIEW_TYPE = SRC_VIEW;
    String DEFAULT_HIDE_CLASSES = "true";
    String DEFAULT_DEPTH = DEPTH_PACKAGE;
    String DEFAULT_COLUMNS = CLASS_COVERAGE_COLUMN + "," + METHOD_COVERAGE_COLUMN + "," + BLOCK_COVERAGE_COLUMN + "," + LINE_COVERAGE_COLUMN + "," + ITEM_NAME_COLUMN;
    String DEFAULT_SORT = ASC + BLOCK_COVERAGE_COLUMN + "," + ASC + ITEM_NAME_COLUMN;
    String DEFAULT_METRICS = METHOD_COVERAGE_COLUMN + MSEPARATOR + "70," + BLOCK_COVERAGE_COLUMN + MSEPARATOR + "80," + LINE_COVERAGE_COLUMN + MSEPARATOR + "80," + CLASS_COVERAGE_COLUMN + MSEPARATOR + "100";

} // end of inteface
// ----------------------------------------------------------------------------
/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: IAppErrorCodes.java,v 1.1.1.1 2004/05/09 16:57:29 vlad_r Exp $
 */
package com.vladium.emma;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface IAppErrorCodes
{
    // public: ................................................................
    
    /** {throwable.toString(), bug report link} */
    String UNEXPECTED_FAILURE       = "UNEXPECTED_FAILURE";
    
    /** {parameter name, value} */
    String INVALID_PARAMETER_VALUE  = "INVALID_PARAMETER_VALUE";
    
    /** {value} */
    String INVALID_COLUMN_NAME      = "INVALID_COLUMN_NAME";
    
    /** {parameter name} */
    String REQUIRED_PARAMETER_MISSING = "REQUIRED_PARAMETER_MISSING"; 
    
    /** {app name} */
    String SECURITY_RESTRICTION                 = "SECURITY_RESTRICTION:";
    
    /** {app name, appclassname, app classloader name} */
    String MAIN_CLASS_BAD_DELEGATION            = "MAIN_CLASS_BAD_DELEGATION";
    
    /** {classname} */
    String MAIN_CLASS_NOT_FOUND                 = "MAIN_CLASS_NOT_FOUND";
    
    /** {classname, throwable.toString()} */
    String MAIN_CLASS_LOAD_FAILURE              = "MAIN_CLASS_LOAD_FAILURE";
    
    /** {classname} */
    String MAIN_METHOD_NOT_FOUND                = "MAIN_METHOD_NOT_FOUND";
    
    /** {classname, throwable.toString()} */
    String MAIN_METHOD_FAILURE                  = "MAIN_METHOD_FAILURE";
    
    //TODO: /** ?? */
    String REPORT_GEN_FAILURE                   = "REPORT_GEN_FAILURE";    
    
    /** [none] */
    String REPORT_IO_FAILURE                    = "REPORT_IO_FAILURE";
    
    /** {classname} */
    String CLASS_STAMP_MISMATCH                 = "CLASS_STAMP_MISMATCH";
    
    /** {dir} */
    String OUT_MKDIR_FAILURE                    = "OUT_MKDIR_FAILURE";
    
    /** [none] */
    String INSTR_IO_FAILURE                     = "INSTR_IO_FAILURE";
    
    /** {filename} */
    String OUT_IO_FAILURE                       = "OUT_IO_FAILURE";
    
    /** [none] */
    String ARGS_IO_FAILURE                      = "ARGS_IO_FAILURE";

} // end of interface
// ----------------------------------------------------------------------------
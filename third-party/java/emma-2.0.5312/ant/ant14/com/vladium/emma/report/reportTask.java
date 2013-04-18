/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: reportTask.java,v 1.1.1.1.2.1 2004/07/08 10:52:11 vlad_r Exp $
 */
package com.vladium.emma.report;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;

import com.vladium.util.IProperties;
import com.vladium.emma.ant.FileTask;
import com.vladium.emma.ant.SuppressableTask;
import com.vladium.emma.report.ReportCfg.Element_HTML;
import com.vladium.emma.report.ReportCfg.Element_TXT;
import com.vladium.emma.report.ReportCfg.Element_XML;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class reportTask extends FileTask implements IReportProperties, IReportEnums
{
    public reportTask (final SuppressableTask parent)
    {
        super (parent);
    }
    
    public void init () throws BuildException
    {
        super.init ();
        
        m_reportCfg = new ReportCfg (getProject (), this);
    }

    
    public void execute () throws BuildException
    {
        if (isEnabled ())
        {
            final String [] reportTypes = m_reportCfg.getReportTypes ();
            
            if ((reportTypes == null) || (reportTypes.length == 0)) // no "txt" default for report processor
                throw (BuildException) newBuildException (getTaskName ()
                    + ": no report types specified: provide at least one of <txt>, <html>, <xml> nested elements", location).fillInStackTrace ();

            String [] files = getDataPath (true);
            if ((files == null) || (files.length == 0))
                throw (BuildException) newBuildException (getTaskName ()
                    + ": no valid input data files have been specified", location).fillInStackTrace ();

            final Path srcpath = m_reportCfg.getSourcepath ();
            
            // combine report and all generic settings:
            final IProperties settings; 
            {
                final IProperties taskSettings = getTaskSettings ();
                final IProperties reportSettings = m_reportCfg.getReportSettings ();
                
                // named report settings override generic named settings and file
                // settings have lower priority than any explicitly named overrides:
                settings = IProperties.Factory.combine (reportSettings, taskSettings);
            }

            final ReportProcessor processor = ReportProcessor.create ();
            
            processor.setDataPath (files); files = null;
            processor.setSourcePath (srcpath != null ? srcpath.list () : null);
            processor.setReportTypes (reportTypes);
            processor.setPropertyOverrides (settings);        
            
            processor.run ();
        }
    }

    
    // sourcepath attribute/element:
    
    public void setSourcepath (final Path path)
    {
        m_reportCfg.setSourcepath (path);
    }
    
    public void setSourcepathRef (final Reference ref)
    {
        m_reportCfg.setSourcepathRef (ref);
    }
    
    public Path createSourcepath ()
    {
        return m_reportCfg.createSourcepath ();
    }
    
    
    // generator elements:
    
    public Element_TXT createTxt ()
    {
        return m_reportCfg.createTxt ();
    }
    
    public Element_HTML createHtml ()
    {
        return m_reportCfg.createHtml ();
    }
    
    public Element_XML createXml ()
    {
        return m_reportCfg.createXml ();
    }
    
    
    // report properties [defaults for all report types]:

    public void setUnits (final UnitsTypeAttribute units)
    {
        m_reportCfg.setUnits (units);
    }    

    public void setDepth (final DepthAttribute depth)
    {
        m_reportCfg.setDepth (depth);
    }
    
    public void setColumns (final String columns)
    {
        m_reportCfg.setColumns (columns);
    }
    
    public void setSort (final String sort)
    {
        m_reportCfg.setSort (sort);
    }
    
    public void setMetrics (final String metrics)
    {
        m_reportCfg.setMetrics (metrics);
    }

    // not supported anymore:
    
//    public void setOutdir (final File dir)
//    {
//        m_reportCfg.setOutdir (dir);
//    }
//    
//    public void setDestdir (final File dir)
//    {
//        m_reportCfg.setDestdir (dir);
//    }
    
    // should not be set at the global level:
    
//    public void setOutfile (final String fileName)
//    {
//        m_reportCfg.setOutfile (fileName);
//    }
    
    public void setEncoding (final String encoding)
    {
        m_reportCfg.setEncoding (encoding);
    }
        
    // protected: .............................................................
        
    // package: ...............................................................
    
    // private: ...............................................................

    
    private ReportCfg m_reportCfg;   

} // end of class
// ----------------------------------------------------------------------------
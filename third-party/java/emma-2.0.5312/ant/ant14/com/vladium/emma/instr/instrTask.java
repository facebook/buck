/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: instrTask.java,v 1.1.1.1.2.1 2004/07/08 10:52:12 vlad_r Exp $
 */
package com.vladium.emma.instr;

import java.io.File;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.EnumeratedAttribute;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;

import com.vladium.util.asserts.$assert;
import com.vladium.emma.ant.FilterTask;
import com.vladium.emma.ant.SuppressableTask;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class instrTask extends FilterTask
{
    // public: ................................................................


    public static final class ModeAttribute extends EnumeratedAttribute
    {
        public String [] getValues ()
        {
            return VALUES;
        }
        
        private static final String [] VALUES = new String [] {"copy", "overwrite", "fullcopy"};

    } // end of nested class
    
    
    public instrTask (final SuppressableTask parent)
    {
        super (parent);
        
        m_outMode = InstrProcessor.OutMode.OUT_MODE_COPY; // default
    }
    
        
    public void execute () throws BuildException
    {
        if (isEnabled ())
        {
            if (m_instrpath == null)
                throw (BuildException) newBuildException (getTaskName ()
                    + ": instrumentation path must be specified", location).fillInStackTrace ();
 
            if ((m_outMode != InstrProcessor.OutMode.OUT_MODE_OVERWRITE) && (m_outDir == null))
                throw (BuildException) newBuildException (getTaskName ()
                    + ": output directory must be specified for '" + m_outMode + "' output mode", location).fillInStackTrace ();
            
            InstrProcessor processor = InstrProcessor.create ();
                
            $assert.ASSERT (m_instrpath != null, "m_instrpath not set");
            processor.setInstrPath (m_instrpath.list (), true); // TODO: an option to set 'canonical'?
            // processor.setDependsMode ()
            processor.setInclExclFilter (getFilterSpecs ());
            $assert.ASSERT (m_outMode != null, "m_outMode not set");
            processor.setOutMode (m_outMode);
            processor.setInstrOutDir (m_outDir != null ? m_outDir.getAbsolutePath () : null);
            processor.setMetaOutFile (m_outFile != null ? m_outFile.getAbsolutePath () : null);
            processor.setMetaOutMerge (m_outFileMerge);
            processor.setPropertyOverrides (getTaskSettings ());
            
            processor.run ();
        }
    }
    
       
    // instrpath attribute/element:
    
    public void setInstrpath (final Path path)
    {
        if (m_instrpath == null)
            m_instrpath = path;
        else
            m_instrpath.append (path);
    }
    
    public void setInstrpathRef (final Reference ref)
    {
        createInstrpath ().setRefid (ref);
    }
    
    public Path createInstrpath ()
    {
        if (m_instrpath == null)
            m_instrpath = new Path (project);
        
        return m_instrpath.createPath ();
    }
    
    
    // outdir|destdir attribute:
    
    public void setOutdir (final File dir)
    {
        if (m_outDir != null)
            throw (BuildException) newBuildException (getTaskName ()
                + ": outdir|destdir attribute already set", location).fillInStackTrace ();
            
        m_outDir = dir;
    }
    
    public void setDestdir (final File dir)
    {
        if (m_outDir != null)
            throw (BuildException) newBuildException (getTaskName ()
                + ": outdir|destdir attribute already set", location).fillInStackTrace ();
        
        m_outDir = dir;
    }
    

    // metadatafile|outfile attribute:

    public void setMetadatafile (final File file)
    {
        if (m_outFile != null)
            throw (BuildException) newBuildException (getTaskName ()
                + ": metadata file attribute already set", location).fillInStackTrace ();
            
        m_outFile = file;
    }
    
    public void setOutfile (final File file)
    {
        if (m_outFile != null)
            throw (BuildException) newBuildException (getTaskName ()
                + ": metadata file attribute already set", location).fillInStackTrace ();
            
        m_outFile = file;
    }
    
    // merge attribute:
     
    public void setMerge (final boolean merge)
    {
        m_outFileMerge = merge ? Boolean.TRUE : Boolean.FALSE;       
    }
    
    
    // mode attribute:
    
    public void setMode (final ModeAttribute mode)
    {        
        final InstrProcessor.OutMode outMode = InstrProcessor.OutMode.nameToMode (mode.getValue ());
        if (outMode == null)
            throw (BuildException) newBuildException (getTaskName ()
                + ": invalid output mode: " + mode.getValue (), location).fillInStackTrace ();
        
        m_outMode = outMode;
    }
    
    // protected: .............................................................
        
    // package: ...............................................................
    
    // private: ...............................................................
    
        
    private Path m_instrpath;
    private InstrProcessor.OutMode m_outMode;
    private File m_outDir;
    private File m_outFile;
    private Boolean m_outFileMerge;

} // end of class
// ----------------------------------------------------------------------------
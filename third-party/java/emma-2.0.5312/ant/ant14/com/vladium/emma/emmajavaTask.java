/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: emmajavaTask.java,v 1.1.1.1.2.2 2004/07/16 23:32:04 vlad_r Exp $
 */
package com.vladium.emma;

import java.io.File;

import com.vladium.util.IProperties;
import com.vladium.util.Strings;
import com.vladium.emma.ant.*;
import com.vladium.emma.instr.FilterCfg;
import com.vladium.emma.instr.FilterCfg.filterElement;
import com.vladium.emma.report.ReportCfg;
import com.vladium.emma.report.IReportEnums.DepthAttribute;
import com.vladium.emma.report.IReportEnums.UnitsTypeAttribute;
import com.vladium.emma.report.ReportCfg.Element_HTML;
import com.vladium.emma.report.ReportCfg.Element_TXT;
import com.vladium.emma.report.ReportCfg.Element_XML;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Java;
import org.apache.tools.ant.types.Commandline;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
class emmajavaTask extends Java
{
    // public: ................................................................
    
    
    public void init () throws BuildException
    {
        super.init ();

        m_verbosityCfg = new VerbosityCfg ();
        m_genericCfg = new GenericCfg (this);
        m_filterCfg = new FilterCfg (this);
        m_reportCfg = new ReportCfg (project, this);
        setEnabled (true);        
    }

    
    public void execute () throws BuildException
    {
        log (IAppConstants.APP_VERBOSE_BUILD_ID, Project.MSG_VERBOSE);
        
        if (getClasspath () == null)
            throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
                + ": this task requires 'classpath' attribute to be set", location).fillInStackTrace ();
            

        if (isEnabled ())
        {
            // fork:
            if (m_forkUserOverride && ! m_fork)
                log (getTaskName () + ": 'fork=\"false\"' attribute setting ignored (this task always forks)", Project.MSG_WARN);
            
            super.setFork (true); // always fork
            
            // add emma libs to the parent task's classpath [to support non-extdir deployment]:
            final Path libClasspath = m_libClasspath;
            if ((libClasspath != null) && (libClasspath.size () > 0))
            {
                super.createClasspath ().append (libClasspath);
            }
            
            // classname|jar (1/2):
            super.setClassname ("emmarun");
            
            // <emmajava> extensions:
            {
                // report types:
                {
                    String reportTypes = Strings.toListForm (m_reportCfg.getReportTypes (), ',');
                    if ((reportTypes == null) || (reportTypes.length () == 0)) reportTypes = "txt";
                    
                    super.createArg ().setValue ("-r");
                    super.createArg ().setValue (reportTypes);
                }
                
                // full classpath scan flag:
                {
                    if (m_scanCoveragePath)
                    {
                        super.createArg ().setValue ("-f");
                    }
                }
                
                // dump raw data flag and options:
                {
                    if (m_dumpSessionData)
                    {
                        super.createArg ().setValue ("-raw");
                        
                        if (m_outFile != null)
                        {
                            super.createArg ().setValue ("-out");
                            super.createArg ().setValue (m_outFile.getAbsolutePath ());
                        }
                        
                        if (m_outFileMerge != null)
                        {
                            super.createArg ().setValue ("-merge");
                            super.createArg ().setValue (m_outFileMerge.booleanValue () ? "y" : "n");
                        }
                    }
                    else
                    {
                        if (m_outFile != null)
                            log (getTaskName () + ": output file attribute ignored ('fullmetadata=\"true\"' not specified)", Project.MSG_WARN);
                        
                        if (m_outFileMerge != null)
                            log (getTaskName () + ": merge attribute setting ignored ('fullmetadata=\"true\"' not specified)", Project.MSG_WARN);
                    }
                } 
                
                // instr filter:
                {
                    final String [] specs = m_filterCfg.getFilterSpecs ();
                    if ((specs != null) && (specs.length > 0))
                    {
                        super.createArg ().setValue ("-ix");
                        super.createArg ().setValue (Strings.toListForm (specs, ','));
                    }
                }
                
                // sourcepath:
                {
                    final Path srcpath = m_reportCfg.getSourcepath ();
                    if (srcpath != null)
                    {
                        super.createArg ().setValue ("-sp");
                        super.createArg ().setValue (Strings.toListForm (srcpath.list (), ','));
                    }
                }
                
                // all other generic settings:
                {
                    final IProperties reportSettings = m_reportCfg.getReportSettings ();
                    final IProperties genericSettings = m_genericCfg.getGenericSettings ();
                    
                    // TODO: another options is to read this file in the forked JVM [use '-props' pass-through]
                    // the best option depends on how ANT resolves relative file names 
                    final IProperties fileSettings = m_genericCfg.getFileSettings ();
                    
                    // verbosity settings use dedicated attributes and hence are more specific
                    // than anything generic:
                    final IProperties verbositySettings = m_verbosityCfg.getSettings ();
                    
                    // (1) file settings have lower priority than any explicitly named overrides
                    // (2) named report settings override generic named settings
                    // (3) verbosity settings use dedicated attributes (not overlapping with report
                    // cfg) and hence are more specific than anything generic
                    final IProperties settings = IProperties.Factory.combine (reportSettings,
                                                 IProperties.Factory.combine (verbositySettings,
                                                 IProperties.Factory.combine (genericSettings,
                                                                              fileSettings)));
                    
                    final String [] argForm = settings.toAppArgsForm ("-D");
                    if (argForm.length > 0)
                    {
                        for (int a = 0; a < argForm.length; ++ a)
                            super.createArg ().setValue (argForm [a]);
                    }
                }
            }
            
            // [assertion: getClasspath() is not null]
            
            // classpath:
            super.createArg ().setValue ("-cp");
            super.createArg ().setPath (getClasspath ());
                        
            // classname|jar (2/2):
            if (getClassname () != null)
                super.createArg ().setValue (getClassname ());
            else if (getJar () != null)
            {
                super.createArg ().setValue ("-jar");
                super.createArg ().setValue (getJar ().getAbsolutePath ());
            }
            else
                throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
                    + "either 'jar' or 'classname' attribute must be set", location).fillInStackTrace ();
                
            // main class args:
            if (m_appArgs != null)
            {
                final String [] args = m_appArgs.getArguments ();
                for (int a = 0; a < args.length; ++ a)
                {
                    super.createArg ().setValue (args [a]); // note: spaces etc are escaped correctly by ANT libs
                }
            }
        }
        else
        {
            // fork:
            super.setFork (m_fork);
            
            // [assertion: getClasspath() is not null]
            
            // classpath:
            super.createClasspath ().append (getClasspath ()); // can't use setClasspath() for obvious reasons
            
            // classname|jar:
            if (getClassname () != null)
                super.setClassname (getClassname ());
            else if (getJar () != null)
                super.setJar (getJar ());
            else
                throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
                    + "either 'jar' or 'classname' attribute must be set", location).fillInStackTrace ();
            
            // main class args:
            if (m_appArgs != null)
            {
                final String [] args = m_appArgs.getArguments ();
                for (int a = 0; a < args.length; ++ a)
                {
                    super.createArg ().setValue (args [a]); // note: spaces etc are escaped correctly by ANT libs
                }
            }    
        }
        
        super.execute ();
    }

    
    
    // <java> overrides [ANT 1.4]:
    
    public void setClassname (final String classname)
    {
        if (getJar () != null)
            throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
                + "'jar' and 'classname' attributes cannot be set at the same time", location).fillInStackTrace ();
            
        m_classname = classname;
    }
    
    public void setJar (final File file)
    {
        if (getClassname () != null)
            throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
                + "'jar' and 'classname' attributes cannot be set at the same time", location).fillInStackTrace ();
            
        m_jar = file;
    }
    
    
    public void setClasspath (final Path path)
    {
        if (m_classpath == null)
            m_classpath = path;
        else
            m_classpath.append (path);
    }
    
    public void setClasspathRef (final Reference ref)
    {
        createClasspath ().setRefid (ref);
    }
    
    public Path createClasspath ()
    {
        if (m_classpath == null)
            m_classpath = new Path (project);
        
        return m_classpath.createPath ();
    }
    
    /**
     * This is already deprecated in ANT v1.4. However, it is still supported by
     * the parent task so I do likewise.
     */ 
    public void setArgs (final String args)
    {
        throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
            + ": disallows using <java>'s deprecated 'args' attribute", location).fillInStackTrace ();
    }

    /**
     * Not overridable.
     */
    public final void setFork (final boolean fork)
    {
        m_fork = fork;
        m_forkUserOverride = true;
    }
    
    /**
     * Not overridable [due to limitations in ANT's Commandline].
     */
    public final Commandline.Argument createArg ()
    {
        if (m_appArgs == null)
            m_appArgs = new Commandline ();
        
        return m_appArgs.createArgument ();
    }
    
    // <java> overrides [ANT 1.5]:
    
    // [nothing at this point]
    
    
    // <emmajava> extensions:
    
    public void setEnabled (final boolean enabled)
    {
        m_enabled = enabled;
    }
    
    // .properties file attribute:
    
    public final void setProperties (final File file)
    {
        m_genericCfg.setProperties (file);
    }

    // generic property element:
    
    public final PropertyElement createProperty ()
    {
        return m_genericCfg.createProperty ();
    }
    
    // verbosity attribute:
    
    public void setVerbosity (final VerbosityCfg.VerbosityAttribute verbosity)
    {
        m_verbosityCfg.setVerbosity (verbosity);
    }
    
    // verbosity class filter attribute:
    
    public void setVerbosityfilter (final String filter)
    {
        m_verbosityCfg.setVerbosityfilter (filter);
    }
    
    // lib classpath attribute [to support non-extdir deployment]:
    
    public final void setLibclasspath (final Path classpath)
    {
        if (m_libClasspath == null)
            m_libClasspath = classpath;
        else
            m_libClasspath.append (classpath);
    }
    
    public final void setLibclasspathRef (final Reference ref)
    {
        if (m_libClasspath == null)
            m_libClasspath = new Path (project);
        
        m_libClasspath.createPath ().setRefid (ref);
    }
    
    // -f flag:
    
    public void setFullmetadata (final boolean full)
    {
        m_scanCoveragePath = full; // defaults to false TODO: maintain the default in a central location
    }
    
    // -raw flag:
    
    public void setDumpsessiondata (final boolean dump)
    {
        m_dumpSessionData = dump;
    }
    
    // -out option:
    
    // sessiondatafile|outfile attribute:
    
    public void setSessiondatafile (final File file)
    {
        if (m_outFile != null)
            throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
                + ": session data file attribute already set", location).fillInStackTrace ();
            
        m_outFile = file;
    }
    
    public void setOutfile (final File file)
    {
        if (m_outFile != null)
            throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
                + ": session data file attribute already set", location).fillInStackTrace ();
            
        m_outFile = file;
    }

//    public void setTofile (final File file)
//    {
//        if (m_outFile != null)
//            throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
//                + ": session data file attribute already set", location).fillInStackTrace ();
//            
//        m_outFile = file;
//    }
//    
//    public void setFile (final File file)
//    {
//        if (m_outFile != null)
//            throw (BuildException) SuppressableTask.newBuildException (getTaskName ()
//                + ": session data file attribute already set", location).fillInStackTrace ();
//            
//        m_outFile = file;
//    }
    
    
    // merge attribute:
     
    public void setMerge (final boolean merge)
    {
        m_outFileMerge = merge ? Boolean.TRUE : Boolean.FALSE;       
    }
    
    // instr filter attribute/element:
    
    public final void setFilter (final String filter)
    {
        m_filterCfg.setFilter (filter);
    }
    
    public final filterElement createFilter ()
    {
        return m_filterCfg.createFilter ();
    }

    
    // TODO: should what's below go inside <report></report> ?
    
    // sourcepath attribute/element:
    
    public final void setSourcepath (final Path path)
    {
        m_reportCfg.setSourcepath (path);
    }
    
    public final void setSourcepathRef (final Reference ref)
    {
        m_reportCfg.setSourcepathRef (ref);
    }
    
    public final Path createSourcepath ()
    {
        return m_reportCfg.createSourcepath ();
    }
    
    
    // generator elements:
    
    public final Element_TXT createTxt ()
    {
        return m_reportCfg.createTxt ();
    }
    
    public final Element_HTML createHtml ()
    {
        return m_reportCfg.createHtml ();
    }
    
    public final Element_XML createXml ()
    {
        return m_reportCfg.createXml ();
    }
    
    
    // report properties [defaults for all report types]:

    public final void setUnits (final UnitsTypeAttribute units)
    {
        m_reportCfg.setUnits (units);
    }    

    public final void setDepth (final DepthAttribute depth)
    {
        m_reportCfg.setDepth (depth);
    }
    
    public final void setColumns (final String columns)
    {
        m_reportCfg.setColumns (columns);
    }
    
    public final void setSort (final String sort)
    {
        m_reportCfg.setSort (sort);
    }
    
    public final void setMetrics (final String metrics)
    {
        m_reportCfg.setMetrics (metrics);
    }
    
    // these are not supported anymore
    
//    public final void setOutdir (final File dir)
//    {
//        m_reportCfg.setOutdir (dir);
//    }
//    
//    public final void setDestdir (final File dir)
//    {
//        m_reportCfg.setDestdir (dir);
//    }

      // should be set at this level [and conflicts with raw data opts]:
          
//    public void setOutfile (final String fileName)
//    {
//        m_reportCfg.setOutfile (fileName);
//    }
    
    public void setEncoding (final String encoding)
    {
        m_reportCfg.setEncoding (encoding);
    }
    
    // protected: .............................................................
    
    
    protected String getClassname ()
    {
        return m_classname;
    }
    
    protected File getJar ()
    {
        return m_jar;
    }
    
    protected Path getClasspath ()
    {
        return m_classpath;
    }
    
    // extended functionality:
    
    protected boolean isEnabled ()
    {
        return m_enabled;
    }

    // package: ...............................................................
    
    // private: ...............................................................
    
    // <java> overrides:
    
    private Path m_classpath;
    private String m_classname;
    private File m_jar;
    private Commandline m_appArgs;
    private boolean m_fork, m_forkUserOverride;
    
    // <emmajava> extensions:
    
    private boolean m_enabled;
    private Path m_libClasspath;
    private /*final*/ VerbosityCfg m_verbosityCfg;
    private /*final*/ GenericCfg m_genericCfg;
    private /*final*/ FilterCfg m_filterCfg;
    private /*final*/ ReportCfg m_reportCfg;
    private boolean m_scanCoveragePath; // defaults to false 
    private boolean m_dumpSessionData; //defaults to false
    private File m_outFile;
    private Boolean m_outFileMerge;

} // end of class
// ----------------------------------------------------------------------------
/*
 * Copyright (c) 2011, Olivier Lamy, Talend
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jvnet.hudson.plugins.mavendepsupdate;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.FilePath;
import hudson.PluginFirstClassLoader;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.maven.MavenModuleSet;
import hudson.model.AbstractProject;
import hudson.model.BuildableItem;
import hudson.model.Cause;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.Project;
import hudson.model.TopLevelItem;
import hudson.remoting.VirtualChannel;
import hudson.scheduler.CronTabList;
import hudson.tasks.Builder;
import hudson.tasks.Maven;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.SystemUtils;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.logging.Logger;

import static hudson.Util.fixNull;

/**
 * @author Olivier Lamy
 */
public class MavenDependencyUpdateTrigger
    extends Trigger<BuildableItem>
{

    private static final Logger LOGGER = Logger.getLogger( MavenDependencyUpdateTrigger.class.getName() );

    private final boolean checkPlugins;

    public static boolean debug = Boolean.getBoolean( "MavenDependencyUpdateTrigger.debug" );

    private static final CLIManager mavenCliManager = new CLIManager();

    @DataBoundConstructor
    public MavenDependencyUpdateTrigger( String cron_value, boolean checkPlugins )
        throws ANTLRException
    {
        super( cron_value );
        this.checkPlugins = checkPlugins;
    }

    @Override
    public void run()
    {
        long start = System.currentTimeMillis();
        ProjectBuildingRequest projectBuildingRequest = null;

        Node node = super.job.getLastBuiltOn();

        if ( node == null )
        {
            // FIXME schedule the first buid ??
            //job.scheduleBuild( arg0, arg1 )
            LOGGER.info( "no previous build found for " + job.getDisplayName() + " so skip maven update trigger" );
            return;
        }

        ClassLoader origClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            PluginWrapper pluginWrapper =
                Hudson.getInstance().getPluginManager().getPlugin( "maven-dependency-update-trigger" );

            boolean isMaster = node == Hudson.getInstance();

            AbstractProject<?, ?> abstractProject = (AbstractProject<?, ?>) super.job;

            FilePath workspace = node.getWorkspaceFor( (TopLevelItem) super.job );

            FilePath moduleRoot = abstractProject.getScm().getModuleRoot( workspace );

            String rootPomPath = moduleRoot.getRemote() + "/" + getRootPomPath();

            File localRepoFile = getLocalRepo( workspace );
            String localRepoPath = localRepoFile == null ? "" : localRepoFile.toString();

            String projectWorkspace = moduleRoot.getRemote();

            Maven.MavenInstallation mavenInstallation = getMavenInstallation();

            mavenInstallation = mavenInstallation.forNode( node, null );

            String mavenHome = mavenInstallation.getHomeDir().getPath();

            String jdkHome = "";

            JDK jdk = getJDK();

            if ( jdk != null )
            {

                jdk = jdk.forNode( node, null );

                jdkHome = jdk == null ? "" : jdk.getHome();

            }

            if ( MavenDependencyUpdateTrigger.debug )
            {
                LOGGER.info( "MavenUpdateChecker with jdkHome:" + jdkHome );
            }
            
            long lastBuildTime = getLastBuildStartTime(abstractProject);
            MavenUpdateChecker checker =
                new MavenUpdateChecker( rootPomPath, localRepoPath, this.checkPlugins, projectWorkspace, isMaster,
                                        mavenHome, jdkHome, lastBuildTime);
            if ( isMaster )
            {
                checker.setClassLoaderParent( (PluginFirstClassLoader) pluginWrapper.classLoader );
            }

            VirtualChannel virtualChannel = node.getChannel();
            FilePath alternateSettings = getAlternateSettings( virtualChannel );
            checker.setAlternateSettings( alternateSettings );

            FilePath globalSettings = getGlobalSettings( virtualChannel );
            checker.setGlobalSettings( globalSettings );

            checker.setUserProperties( getUserProperties() );

            checker.setActiveProfiles( getActiveProfiles() );

            LOGGER.info( "run MavenUpdateChecker for project " + job.getName() + " on node " + node.getDisplayName() );

            MavenUpdateCheckerResult mavenUpdateCheckerResult = virtualChannel.call( checker );

            if ( debug )
            {
                StringBuilder debugLines = new StringBuilder(
                    "MavenUpdateChecker for project " + job.getName() + " on node " + node.getDisplayName() ).append(
                    SystemUtils.LINE_SEPARATOR );
                for ( String line : mavenUpdateCheckerResult.getDebugLines() )
                {
                    debugLines.append( line ).append( SystemUtils.LINE_SEPARATOR );
                }
                LOGGER.info( debugLines.toString() );
            }

            if ( mavenUpdateCheckerResult.getFileUpdatedNames().size() > 0 )
            {
                StringBuilder stringBuilder = new StringBuilder(
                    "MavenUpdateChecker for project " + job.getName() + " on node " + node.getDisplayName() );
                stringBuilder.append( " , snapshotDownloaded so triggering a new build : " ).append(
                    SystemUtils.LINE_SEPARATOR );
                for ( String fileName : mavenUpdateCheckerResult.getFileUpdatedNames() )
                {
                    stringBuilder.append( " * " + fileName ).append( SystemUtils.LINE_SEPARATOR );
                }
                job.scheduleBuild( 0, new MavenDependencyUpdateTriggerCause(
                    mavenUpdateCheckerResult.getFileUpdatedNames() ) );
                LOGGER.info( stringBuilder.toString() );
            }

            long end = System.currentTimeMillis();
            LOGGER.info(
                "time to run MavenUpdateChecker for project " + job.getName() + " on node " + node.getDisplayName()
                    + " : " + ( end - start ) + " ms" );
        }
        catch ( Exception e )
        {
            LOGGER.warning( "ignore " + e.getMessage() );
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( origClassLoader );
        }
    }

    private long getLastBuildStartTime(AbstractProject<?,?> abstractProject)
    {
        TimeZone tz = abstractProject.getLastBuild().getTimestamp().getTimeZone();
        long timestamp = abstractProject.getLastBuild().getStartTimeInMillis();
        Calendar calDate = Calendar.getInstance(tz);
        calDate.setTimeInMillis(timestamp);
        Date localTime = calDate.getTime();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String utcString = sdf.format(localTime);
        
        return Long.valueOf(utcString);
    }

    private File getLocalRepo( FilePath workspace )
    {
        boolean usePrivateRepo = usePrivateRepo();
        if ( usePrivateRepo )
        {
            return new File( workspace.getRemote(), ".repository" );
        }
        return null;
    }

    @Extension
    public static class DescriptorImpl
        extends TriggerDescriptor
    {
        public boolean isApplicable( Item item )
        {
            return item instanceof BuildableItem;
        }

        public String getDisplayName()
        {
            return Messages.plugin_title();
        }

        @Override
        public String getHelpFile()
        {
            return "/plugin/maven-dependency-update-trigger/help.html";
        }

        /**
         * Performs syntax check.
         */
        public FormValidation doCheck( @QueryParameter String value )
        {
            try
            {
                String msg = CronTabList.create( fixNull( value ) ).checkSanity();
                if ( msg != null )
                {
                    return FormValidation.warning( msg );
                }
                return FormValidation.ok();
            }
            catch ( ANTLRException e )
            {
                return FormValidation.error( e.getMessage() );
            }
        }
    }

    public static class MavenDependencyUpdateTriggerCause
        extends Cause
    {
        private List<String> snapshotsDownloaded;

        MavenDependencyUpdateTriggerCause( List<String> snapshotsDownloaded )
        {
            this.snapshotsDownloaded = snapshotsDownloaded;
        }

        @Override
        public String getShortDescription()
        {
            StringBuilder sb = new StringBuilder( "maven SNAPSHOT dependency update cause : " );
            if ( snapshotsDownloaded != null && snapshotsDownloaded.size() > 0 )
            {
                sb.append( "," );
                for ( String snapshot : snapshotsDownloaded )
                {
                    sb.append( snapshot );
                }
                sb.append( " " );
            }

            return sb.toString();
        }

        @Override
        public boolean equals( Object o )
        {
            return o instanceof MavenDependencyUpdateTriggerCause;
        }

        @Override
        public int hashCode()
        {
            return 5 * 2;
        }
    }

    private boolean usePrivateRepo()
    {
        boolean usePrivate = false;

        Project<?,?> fp = (Project<?,?>) this.job;
        for ( Builder b : fp.getBuilders() )
        {
            if ( b instanceof Maven )
            {
                if ( ( (Maven) b ).usePrivateRepository )
                {
                    usePrivate = true;
                }
            }
        }

        // check if there is a method called usesPrivateRepository
        if (!usePrivate)
        {
            try
            {
                Method method = this.job.getClass().getMethod( "usesPrivateRepository", (Class<?>) null );
                Boolean bool = (Boolean) method.invoke( this.job, (Object[]) null );
                return bool.booleanValue();
            }
            catch ( SecurityException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( NoSuchMethodException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalArgumentException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalAccessException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( InvocationTargetException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
        }

        return usePrivate;
    }

    private Map<String, MavenProject> getProjectMap( List<MavenProject> projects )
    {
        Map<String, MavenProject> index = new LinkedHashMap<String, MavenProject>();

        for ( MavenProject project : projects )
        {
            String projectId = ArtifactUtils.key( project.getGroupId(), project.getArtifactId(), project.getVersion() );
            index.put( projectId, project );
        }

        return index;
    }

    private FilePath getAlternateSettings( VirtualChannel virtualChannel )
    {
        FilePath altSettings = null;
        //-s,--settings or from configuration for maven native project

        Project<?,?> fp = (Project<?,?>) this.job;
        for ( Builder b : fp.getBuilders() )
        {
            if ( b instanceof Maven )
            {
                String targets = ( (Maven) b ).getTargets();
                String[] args = Util.tokenize( targets );
                if ( args == null )
                {
                    altSettings = null;
                }
                CommandLine cli = getCommandLine( args );
                if ( cli != null && cli.hasOption( CLIManager.ALTERNATE_USER_SETTINGS ) )
                {
                    altSettings = new FilePath( virtualChannel, cli.getOptionValue( CLIManager.ALTERNATE_POM_FILE ) );
                }
            }
        }

        // check if there is a method called getAlternateSettings
        if (altSettings == null)
        {
            try
            {
                Method method = this.job.getClass().getMethod( "getAlternateSettings", (Class<?>) null );
                String alternateSettings = (String) method.invoke( this.job, (Object[]) null );
                altSettings = alternateSettings != null ? new FilePath( virtualChannel, alternateSettings ) : null;
            }
            catch ( SecurityException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( NoSuchMethodException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalArgumentException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalAccessException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( InvocationTargetException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
        }

        return altSettings;
    }

    private FilePath getGlobalSettings( VirtualChannel virtualChannel )
    {
        //-gs,--global-settings
        FilePath globalSettings = null;

        Project<?,?> fp = (Project<?,?>) this.job;
        for ( Builder b : fp.getBuilders() )
        {
            if ( b instanceof Maven )
            {
                String targets = ( (Maven) b ).getTargets();
                String[] args = Util.tokenize( targets );
                if ( args == null )
                {
                    globalSettings = null;
                }
                CommandLine cli = getCommandLine( args );
                if ( cli != null && cli.hasOption( CLIManager.ALTERNATE_GLOBAL_SETTINGS ) )
                {
                    globalSettings = new FilePath( virtualChannel,
                                         cli.getOptionValue( CLIManager.ALTERNATE_GLOBAL_SETTINGS ) );
                }
            }
        }
        return globalSettings;
    }

    private Properties getUserProperties()
        throws IOException
    {
        Properties props = new Properties();
        Project<?,?> fp = (Project<?,?>) this.job;
        for ( Builder b : fp.getBuilders() )
        {
            if ( b instanceof Maven )
            {
                String properties = ( (Maven) b ).properties;
                props = load( properties );
            }
        }
        return props;
    }

    private Properties load( String properties )
        throws IOException
    {
        Properties p = new Properties();
        if (properties != null)
        {
            p.load( new ByteArrayInputStream( properties.getBytes() ) );
        }
        return p;
    }

    private String getRootPomPath()
    {
        String rootPomPath = null;

        Project<?,?> p = (Project<?,?>) this.job;
        for ( Builder b : p.getBuilders() )
        {
            if ( b instanceof Maven )
            {
                String targets = ( (Maven) b ).getTargets();
                String[] args = Util.tokenize( targets );

                if ( args == null )
                {
                    rootPomPath = null;
                }
                CommandLine cli = getCommandLine( args );
                if ( cli != null && cli.hasOption( CLIManager.ALTERNATE_POM_FILE ) )
                {
                    rootPomPath = cli.getOptionValue( CLIManager.ALTERNATE_POM_FILE );
                }
            }
        }

        // check if there is a method called getRootPOM
        if (rootPomPath == null)
        {
            try
            {
                Method method = this.job.getClass().getMethod( "getRootPOM", (Class<?>) null );
                String rootPom = (String) method.invoke( this.job, (Object[]) null );
                rootPomPath = rootPom;
            }
            catch ( SecurityException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( NoSuchMethodException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalArgumentException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalAccessException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( InvocationTargetException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
        }

        return rootPomPath == null ? "pom.xml" : rootPomPath;
    }

    private List<String> getActiveProfiles()
    {
        List<String> activeProfiles = null;

        Project<?,?> p = (Project<?,?>) this.job;
        for ( Builder b : p.getBuilders() )
        {
            if ( b instanceof Maven )
            {
                String targets = ( (Maven) b ).getTargets();
                String[] args = Util.tokenize( targets );

                if ( args == null )
                {
                    activeProfiles = null;
                }
                CommandLine cli = getCommandLine( args );
                if ( cli != null && cli.hasOption( CLIManager.ACTIVATE_PROFILES ) )
                {
                    activeProfiles = Arrays.asList( cli.getOptionValues( CLIManager.ACTIVATE_PROFILES ) );
                }
            }
        }

        // check if there is a method called getGoals
        if (activeProfiles == null)
        {
            try
            {
                Method method = this.job.getClass().getMethod( "getGoals", (Class<?>) null );
                String goals = (String) method.invoke( this.job, (Object[]) null );
                String[] args = Util.tokenize( goals );
                if ( args == null )
                {
                    activeProfiles = Collections.emptyList();
                }
                CommandLine cli = getCommandLine( args );
                if ( cli != null && cli.hasOption( CLIManager.ACTIVATE_PROFILES ) )
                {
                    activeProfiles = Arrays.asList( cli.getOptionValues( CLIManager.ACTIVATE_PROFILES ) );
                }
            }
            catch ( SecurityException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( NoSuchMethodException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalArgumentException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( IllegalAccessException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
            catch ( InvocationTargetException e )
            {
                LOGGER.warning( "ignore " + e.getMessage() );
            }
        }
        return activeProfiles;
    }

    private CommandLine getCommandLine( String[] args )
    {
        try
        {
            return mavenCliManager.parse( args );
        }
        catch ( ParseException e )
        {
            LOGGER.info( "ignore error parsing maven args " + e.getMessage() );
            return null;
        }
    }

    private Maven.MavenInstallation getMavenInstallation()
    {
        Maven.MavenInstallation installation = null;

        if ( this.job instanceof MavenModuleSet )
        {
            installation = ( (MavenModuleSet) this.job ).getMaven();
        }

        if (installation == null)
        {
            Project<?,?> fp = (Project<?,?>) this.job;
            for ( Builder b : fp.getBuilders() )
            {
                if ( b instanceof Maven )
                {
                    installation = ( (Maven) b ).getMaven();
                }
            }
            if (installation == null)
            {
                // null so return first found
                for ( Maven.MavenInstallation i : MavenModuleSet.DESCRIPTOR.getMavenDescriptor().getInstallations() )
                {
                    installation = i;
                }
            }
        }

        return installation;
    }

    private JDK getJDK()
    {
        JDK jdk = null;
        if ( this.job instanceof MavenModuleSet )
        {
            jdk = ( (MavenModuleSet) this.job ).getJDK();
        }
        Project<?,?> fp = (Project<?,?>) this.job;
        jdk = fp.getJDK();

        return jdk;
    }

}

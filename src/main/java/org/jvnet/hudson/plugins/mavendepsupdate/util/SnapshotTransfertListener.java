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
package org.jvnet.hudson.plugins.mavendepsupdate.util;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jvnet.hudson.plugins.mavendepsupdate.MavenDependencyUpdateTrigger;
import org.sonatype.aether.transfer.TransferCancelledException;
import org.sonatype.aether.transfer.TransferEvent;
import org.sonatype.aether.transfer.TransferListener;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * NOTE : <b>this class is not designed for external use so it can change without any prior notice</b>
 *
 * @author Olivier Lamy
 * @since 1.1
 */
public class SnapshotTransfertListener
    implements TransferListener, Serializable
{
    private static final Logger LOGGER = Logger.getLogger( SnapshotTransfertListener.class.getName() );
    
    private final Long lastBuild;

    private boolean snapshotDownloaded = false;

    private List<String> snapshots = new ArrayList<String>();

    public SnapshotTransfertListener()
    {
        this.lastBuild = Long.MAX_VALUE;
    }

    public SnapshotTransfertListener(Long lastBuild)
    {
        this.lastBuild = lastBuild;
    }
    
    public void transferCorrupted( TransferEvent transferEvent )
        throws TransferCancelledException
    {
        // no op
    }

    public void transferFailed( TransferEvent transferEvent )
    {
        // no op       
    }

    public void transferInitiated( TransferEvent transferEvent )
        throws TransferCancelledException
    {
        // no op
    }

    public void transferProgressed( TransferEvent transferEvent )
        throws TransferCancelledException
    {
        // no op
    }

    public void transferStarted( TransferEvent transferEvent )
        throws TransferCancelledException
    {
        // no op
    }

    public void transferSucceeded( TransferEvent transferEvent )
    {
        if ( transferEvent != null && transferEvent.getResource() != null )
        {
            File file = transferEvent.getResource().getFile();
            if ( file != null && transferEvent.getResource().getResourceName().contains( "SNAPSHOT" ) )
            {
                // filtering on maven metadata
                boolean isArtifact = !isMetaData( file );
                if ( isArtifact )
                {
                    if ( MavenDependencyUpdateTrigger.debug )
                    {
                        LOGGER.info( "download " + file.getName() );
                    }
                    snapshots.add( file.getName() );
                    snapshotDownloaded = true;
                }
                else
                {
                    try {
                        String fileContents = FileUtils.readFileToString(file);
                        String lastUpdatedString = StringUtils.substringBetween(fileContents, "<lastUpdated>", "</lastUpdated>");
                        long lastUpdated = Long.valueOf(lastUpdatedString);
                        if (lastUpdated >= this.lastBuild)
                        {
                            snapshots.add( file.getName() );
                            snapshotDownloaded = true;
                        }
                    }
                    catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public boolean isSnapshotDownloaded()
    {
        return snapshotDownloaded;
    }

    public List<String> getSnapshots()
    {
        return snapshots;
    }
    
    private boolean isMetaData(File file)
    {
        return StringUtils.contains( file.getName(), "maven-metadata" ) && StringUtils.endsWith( file.getName(), ".xml" );
    }
    
}

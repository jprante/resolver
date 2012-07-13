/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shrinkwrap.resolver.impl.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.shrinkwrap.resolver.api.NoResolutionException;
import org.jboss.shrinkwrap.resolver.api.NonUniqueResolutionException;
import org.jboss.shrinkwrap.resolver.api.formatprocessor.FileFormatProcessor;
import org.jboss.shrinkwrap.resolver.api.formatprocessor.FormatProcessor;
import org.jboss.shrinkwrap.resolver.api.formatprocessor.InputStreamFormatProcessor;
import org.jboss.shrinkwrap.resolver.api.maven.MavenFormatStage;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolutionFilter;
import org.jboss.shrinkwrap.resolver.api.maven.ResolvedArtifactInfo;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.resolution.ArtifactResult;

/**
 *
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 */
public class MavenFormatStageImpl implements MavenFormatStage {
    private static final Logger log = Logger.getLogger(MavenFormatStageImpl.class.getName());

    private static final ArtifactMapper REACTOR_MAPPER = new ArtifactMapper() {

        @Override
        public File map(ArtifactResult artifactResult) throws IllegalArgumentException {
            Validate.notNull(artifactResult, "ArtifactResult must not be null");
            if (!isMappable(artifactResult)) {
                throw new IllegalArgumentException(MessageFormat.format("Artifact {0} cannot be mapped to a file.",
                        artifactResult.getArtifact()));
            }

            Artifact artifact = artifactResult.getArtifact();
            // FIXME: this is not a safe assumption, file can have a different name
            if ("pom.xml".equals(artifact.getFile().getName())) {

                String artifactId = artifact.getArtifactId();
                String extension = artifact.getExtension();

                File root = new File(artifact.getFile().getParentFile(), "target/classes");
                try {
                    File archive = File.createTempFile(artifactId + "-", "." + extension);
                    archive.deleteOnExit();
                    IOUtil.packageDirectories(archive, root);
                    return archive;
                } catch (IOException e) {
                    throw new IllegalArgumentException("Unable to get artifact " + artifactId + " from the classpath", e);
                }

            } else {
                return artifact.getFile();
            }
        }

        @Override
        public boolean isMappable(ArtifactResult artifactResult) throws IllegalArgumentException {

            return true;

            // FIXME this differs for ResolvedArtifactInfo
            /*
             * Artifact a = artifactResult.getArtifact(); // skip all pom artifacts if ("pom".equals(a.getExtension())) { return
             * false; }
             */

        }
    };

    private Collection<ArtifactResult> artifacts;
    // FIXME not used yet
    private MavenResolutionFilter postResolutionFilter;

    public MavenFormatStageImpl(Collection<ArtifactResult> artifacts, MavenResolutionFilter postResolutionFilter) {
        this.artifacts = artifacts;
        this.postResolutionFilter = postResolutionFilter;
    }

    @Override
    public File[] as(Class<File> type) throws IllegalArgumentException {
        return as(File.class, FileFormatProcessor.INSTANCE);
    }

    @Override
    public InputStream[] as(Class<InputStream> type) throws IllegalArgumentException {
        return as(InputStream.class, InputStreamFormatProcessor.INSTANCE);
    }

    @Override
    public File asSingle(Class<File> type) throws IllegalArgumentException, NonUniqueResolutionException, NoResolutionException {
        return asSingle(File.class, FileFormatProcessor.INSTANCE);
    }

    @Override
    public InputStream asSingle(Class<InputStream> type) throws IllegalArgumentException, NonUniqueResolutionException,
            NoResolutionException {
        return asSingle(InputStream.class, InputStreamFormatProcessor.INSTANCE);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <RETURNTYPE> RETURNTYPE[] as(Class<RETURNTYPE> type, FormatProcessor<RETURNTYPE> processor)
            throws IllegalArgumentException {

        List<RETURNTYPE> list = new ArrayList<RETURNTYPE>();

        for (ArtifactResult artifact : artifacts) {
            if (REACTOR_MAPPER.isMappable(artifact)) {
                list.add(processor.process(REACTOR_MAPPER.map(artifact)));
            } else {
                log.log(Level.INFO, "Removed artifact {0} from archive, it cannot be mapped to a file", artifact);
            }
        }

        // we need to convert to an array of specified return type
        // due to generics this is the only way
        RETURNTYPE[] array = (RETURNTYPE[]) Array.newInstance(type, list.size());
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    @Override
    public <RETURNTYPE> RETURNTYPE asSingle(Class<RETURNTYPE> type, FormatProcessor<RETURNTYPE> processor)
            throws IllegalArgumentException, NonUniqueResolutionException, NoResolutionException {

        Collection<RETURNTYPE> collection = new ArrayList<RETURNTYPE>();

        for (ArtifactResult artifact : artifacts) {
            if (REACTOR_MAPPER.isMappable(artifact)) {
                collection.add(processor.process(REACTOR_MAPPER.map(artifact)));
            } else {
                log.log(Level.INFO, "Removed artifact {0} from archive, it cannot be mapped to a file", artifact);
            }
        }

        if (collection.isEmpty()) {
            throw new NoResolutionException("Unable to resolve dependencies, none of them were found.");
        }
        if (collection.size() != 1) {

            StringBuilder sb = new StringBuilder();
            for (RETURNTYPE artifact : collection) {
                sb.append(artifact).append("\n");
            }
            // delete last two characters
            if (sb.lastIndexOf("\n") != -1) {
                sb.deleteCharAt(sb.length() - 1);
            }

            throw new NonUniqueResolutionException(
                    MessageFormat
                            .format("Resolution resolved more than a single artifact ({0} artifact(s)), unable to determine which one should used.\nComplete list of resolved artifacts:\n{1}",
                                    collection.size(), sb));
        }

        return collection.iterator().next();
    }

    @Override
    public ResolvedArtifactInfo[] as(Class<ResolvedArtifactInfo> type) throws IllegalArgumentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResolvedArtifactInfo asSingle(Class<ResolvedArtifactInfo> type) throws IllegalArgumentException,
            NonUniqueResolutionException, NoResolutionException {
        throw new UnsupportedOperationException();
    }

    /**
     * Maps an artifact to a file. This allows ShrinkWrap Maven resolver to package reactor releted dependencies.
     *
     * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
     *
     */
    private interface ArtifactMapper {

        boolean isMappable(ArtifactResult artifactResult) throws IllegalArgumentException;

        File map(ArtifactResult artifactResult) throws IllegalArgumentException;
    }

}
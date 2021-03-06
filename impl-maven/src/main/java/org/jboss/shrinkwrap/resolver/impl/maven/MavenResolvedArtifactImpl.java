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

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.shrinkwrap.resolver.api.maven.MavenArtifactInfo;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;
import org.jboss.shrinkwrap.resolver.impl.maven.util.IOUtil;
import org.jboss.shrinkwrap.resolver.impl.maven.util.Validate;
import org.jboss.shrinkwrap.resolver.spi.format.FormatProcessor;
import org.jboss.shrinkwrap.resolver.spi.format.FormatProcessors;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.graph.DependencyNode;
import org.sonatype.aether.resolution.ArtifactResult;

/**
 * Immutable implementation of {@link MavenResolvedArtifact}.
 *
 * @author <a href="mailto:mmatloka@gmail.com">Michal Matloka</a>
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 */
public class MavenResolvedArtifactImpl extends MavenArtifactInfoImpl implements MavenResolvedArtifact {

    private static final Logger log = Logger.getLogger(MavenResolvedArtifactImpl.class.getName());

    private final File file;

    private MavenResolvedArtifactImpl(MavenCoordinate mavenCoordinate, String resolvedVersion, boolean snapshotVersion,
        String extension, File file, MavenArtifactInfo[] dependencies) {
        super(mavenCoordinate, resolvedVersion, snapshotVersion, extension, dependencies);
        this.file = file;
    }

    private MavenResolvedArtifactImpl(final Artifact artifact, final List<DependencyNode> children) {
        super(artifact, children);
        this.file = artifactToFile(artifact);
    }

    /**
     * Creates MavenResolvedArtifact based on ArtifactResult.
     *
     * @param artifactResult
     * @return
     */
    static MavenResolvedArtifact fromArtifactResult(final ArtifactResult artifactResult) {
        final Artifact artifact = artifactResult.getArtifact();
        final List<DependencyNode> children = artifactResult.getRequest().getDependencyNode().getChildren();
        return new MavenResolvedArtifactImpl(artifact, children);
    }

    @Override
    public <RETURNTYPE> RETURNTYPE as(Class<RETURNTYPE> returnType) {
        if (returnType == null) {
            throw new IllegalArgumentException("Type must be specified.");
        }

        final FormatProcessor<? super MavenResolvedArtifact, RETURNTYPE> processor = FormatProcessors.find(
            MavenResolvedArtifact.class, returnType);

        return processor.process(this, returnType);
    }

    @Override
    public File asFile() {
        return file;
    }

    @Override
    public InputStream asInputStream() {
        return as(InputStream.class);
    }

    @Override
    public MavenResolvedArtifact asResolvedArtifact() {
        return as(MavenResolvedArtifact.class);
    }

    @Override
    public String toString() {
        return "MavenResolvedArtifactImpl [mavenCoordinate=" + mavenCoordinate + ", resolvedVersion=" + resolvedVersion
            + ", snapshotVersion=" + snapshotVersion + ", extension=" + extension + ", dependencies="
            + Arrays.toString(dependencies) + "]";
    }

    /**
     * Maps an artifact to a file. This allows ShrinkWrap Maven resolver to package reactor related dependencies.
     */
    private static File artifactToFile(final Artifact artifact) throws IllegalArgumentException {
        if (artifact == null) {
            throw new IllegalArgumentException("ArtifactResult must not be null");
        }

        // FIXME: this is not a safe assumption, file can have a different name
        if ("pom.xml".equals(artifact.getFile().getName())) {

            String artifactId = artifact.getArtifactId();
            String extension = artifact.getExtension();

            File root = new File(artifact.getFile().getParentFile(), "target/classes");
            try {
                File archive = File.createTempFile(artifactId + "-", "." + extension);
                archive.deleteOnExit();
                PackageDirHelper.packageDirectories(archive, root);
                return archive;
            } catch (IOException e) {
                throw new IllegalArgumentException("Unable to get artifact " + artifactId + " from the classpath", e);

            }

        } else {
            return artifact.getFile();
        }
    }

    /**
     * I/O Utilities needed by the enclosing class
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     */
    private static class PackageDirHelper {
        private PackageDirHelper() {
            throw new UnsupportedOperationException("No instances should be created; stateless class");
        }

        private static void safelyClose(final Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (final IOException ignore) {
                    if (log.isLoggable(Level.FINER)) {
                        log.finer("Could not close stream due to: " + ignore.getMessage() + "; ignoring");
                    }
                }
            }
        }

        static void packageDirectories(final File outputFile, final File... directories) throws IOException {

            Validate.notNullAndNoNullValues(directories, "Directories to be packaged must be specified");

            final ZipOutputStream zipFile = new ZipOutputStream(new FileOutputStream(outputFile));

            for (File directory : directories) {
                for (String entry : fileListing(directory)) {
                    FileInputStream fis = null;
                    try {
                        fis = new FileInputStream(new File(directory, entry));
                        zipFile.putNextEntry(new ZipEntry(entry));
                        IOUtil.copy(fis, zipFile);
                    } finally {
                        safelyClose(fis);
                    }
                }
            }
            safelyClose(zipFile);
        }

        private static List<String> fileListing(final File directory) {
            final List<String> list = new ArrayList<String>();
            generateFileList(list, directory, directory);
            return list;
        }

        private static void generateFileList(final List<String> list, final File root, final File file) {
            if (file.isFile()) {
                list.add(file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1));
            } else if (file.isDirectory()) {
                for (File next : file.listFiles()) {
                    generateFileList(list, root, next);
                }
            }
        }
    }
}

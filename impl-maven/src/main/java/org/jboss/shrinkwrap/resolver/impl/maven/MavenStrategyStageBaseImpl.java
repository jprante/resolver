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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.shrinkwrap.resolver.api.NoResolvedResultException;
import org.jboss.shrinkwrap.resolver.api.maven.MavenFormatStage;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.MavenStrategyStage;
import org.jboss.shrinkwrap.resolver.api.maven.MavenStrategyStageBase;
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType;
import org.jboss.shrinkwrap.resolver.api.maven.ScopeType;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependencies;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependency;
import org.jboss.shrinkwrap.resolver.api.maven.filter.MavenResolutionFilter;
import org.jboss.shrinkwrap.resolver.api.maven.strategy.MavenResolutionStrategy;
import org.jboss.shrinkwrap.resolver.api.maven.strategy.NonTransitiveStrategy;
import org.jboss.shrinkwrap.resolver.api.maven.strategy.TransitiveStrategy;
import org.jboss.shrinkwrap.resolver.impl.maven.convert.MavenConverter;
import org.jboss.shrinkwrap.resolver.impl.maven.util.Validate;
import org.sonatype.aether.collection.CollectRequest;
import org.sonatype.aether.collection.DependencyCollectionException;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.resolution.ArtifactResolutionException;
import org.sonatype.aether.resolution.DependencyResolutionException;

/**
 * Base support for implementations of {@link MavenStrategyStage}
 *
 * @author <a href="mailto:kpiwko@redhat.com">Karel Piwko</a>
 * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
 * @author <a href="mailto:mmatloka@gmail.com">Michal Matloka</a>
 */
public abstract class MavenStrategyStageBaseImpl<STRATEGYSTAGETYPE extends MavenStrategyStageBase<STRATEGYSTAGETYPE, FORMATSTAGETYPE>, FORMATSTAGETYPE extends MavenFormatStage>
        implements MavenStrategyStageBase<STRATEGYSTAGETYPE, FORMATSTAGETYPE>, MavenWorkingSessionContainer {

    private static final Logger log = Logger.getLogger(MavenStrategyStageBaseImpl.class.getName());

    private final MavenWorkingSession session;

    public MavenStrategyStageBaseImpl(final MavenWorkingSession session) {
        this.session = session;
    }

    @Override
    public FORMATSTAGETYPE withTransitivity() {
        return using(TransitiveStrategy.INSTANCE);
    }

    @Override
    public FORMATSTAGETYPE withoutTransitivity() {
        return using(NonTransitiveStrategy.INSTANCE);
    }

    @Override
    public MavenWorkingSession getMavenWorkingSession() {
        return session;
    }

    private List<MavenDependency> preFilter(MavenResolutionFilter[] filters,
            List<MavenDependency> dependenciesForResolution, final List<MavenDependency> declaredDependencies) {

        assert filters != null : "Filters must be specified, even if empty";

        final List<MavenDependency> filtered = new ArrayList<MavenDependency>();
        depsLoop: for (MavenDependency candidate : declaredDependencies) {
            for (final MavenResolutionFilter filter : filters) {
                if (!filter.accepts(candidate, dependenciesForResolution)) {
                    continue depsLoop;
                }
            }
            filtered.add(candidate);
        }

        return filtered;
    }

    @Override
    public FORMATSTAGETYPE using(final MavenResolutionStrategy strategy) throws IllegalArgumentException {
        // first, get dependencies specified for resolution in the session
        Validate.notEmpty(session.getDependenciesForResolution(), "No dependencies were set for resolution");

        final CollectRequest request = createCollectRequest(strategy);

        final Collection<MavenResolvedArtifact> artifactResults = retrieveArtifacts(strategy, request);

        final Collection<MavenResolvedArtifact> filteredArtifacts = filterArtifacts(artifactResults);

        // Clear dependencies to be resolved (for the next request); we've already sent this request
        this.session.getDependenciesForResolution().clear();

        // Proceed to format stage
        return this.createFormatStage(filteredArtifacts);
    }

    /**
     * Create artifacts collect request.
     * @param strategy
     * @return
     */
    private CollectRequest createCollectRequest(final MavenResolutionStrategy strategy) {
        final List<MavenDependency> depsForResolution = Collections.unmodifiableList(new ArrayList<MavenDependency>(
            session.getDependenciesForResolution()));
        final List<MavenDependency> prefilteredDependencies = preFilter(strategy.getPreResolutionFilters(),
            depsForResolution, depsForResolution);
        final List<MavenDependency> prefilteredDepsList = new ArrayList<MavenDependency>(prefilteredDependencies);
        final List<MavenDependency> depManagement = new ArrayList<MavenDependency>(session.getDependencyManagement());

        final List<RemoteRepository> repos = session.getRemoteRepositories();
        return new CollectRequest(MavenConverter.asDependencies(prefilteredDepsList),
            MavenConverter.asDependencies(depManagement), repos);
    }

    /**
     * Retrieve artifacts from Maven repository.
     * @param strategy
     * @param request
     * @return
     */
    private Collection<MavenResolvedArtifact> retrieveArtifacts(final MavenResolutionStrategy strategy,
        final CollectRequest request) {
        try {
            final Collection<MavenResolvedArtifact> retrievedArtifacts = session.execute(request, strategy.getResolutionFilters());
            return Collections.unmodifiableCollection(retrievedArtifacts);
        } catch (DependencyResolutionException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                if (cause instanceof ArtifactResolutionException) {
                    throw new NoResolvedResultException("Unable to get artifact from the repository, reason: "
                        + e.getMessage());
                } else if (cause instanceof DependencyCollectionException) {
                    throw new NoResolvedResultException(
                        "Unable to collect dependency tree for given dependencies, reason: " + e.getMessage());
                }
                throw new NoResolvedResultException(
                    "Unable to collect/resolve dependency tree for a resulution, reason: " + e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    /**
     * Run post-resolution filtering to weed out POMs.
     *
     * @param artifactResults
     * @return
     */
    private Collection<MavenResolvedArtifact> filterArtifacts(final Collection<MavenResolvedArtifact> artifactResults) {

        final MavenResolutionFilter postResolutionFilter = RestrictPomArtifactFilter.INSTANCE;
        final Collection<MavenResolvedArtifact> filteredArtifacts = new ArrayList<MavenResolvedArtifact>();
        final List<MavenDependency> emptyList = Collections.emptyList();

        for (final MavenResolvedArtifact artifact : artifactResults) {
            final MavenDependency dependency = MavenDependencies.createDependency(artifact.getCoordinate(),
                ScopeType.COMPILE, false);
            // Empty lists OK here because we know the RestrictPOM Filter doesn't consult them
            if (postResolutionFilter.accepts(dependency, emptyList)) {
                filteredArtifacts.add(artifact);
            }
        }
        return Collections.unmodifiableCollection(filteredArtifacts);
    }

    /**
     * {@link MavenResolutionFilter} implementation which does not allow POMs to pass through
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     */
    private enum RestrictPomArtifactFilter implements MavenResolutionFilter {

        INSTANCE;

        /**
         * {@inheritDoc}
         *
         * @see org.jboss.shrinkwrap.resolver.api.maven.filter.MavenResolutionFilter#accepts(org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenDependency,
         * java.util.List)
         */
        @Override
        public boolean accepts(final MavenDependency coordinate, final List<MavenDependency> dependenciesForResolution)
                throws IllegalArgumentException {
            if (PackagingType.POM.equals(coordinate.getPackaging())) {
                if (log.isLoggable(Level.FINER)) {
                    log.finer("Filtering out POM dependency resolution: " + coordinate
                            + "; its transitive dependencies will be included");
                }

                return false;
            }
            return true;
        }

    }

    /**
     * {@inheritDoc}
     *
     * @see org.jboss.shrinkwrap.resolver.api.maven.MavenStrategyStageBase#withClassPathResolution(boolean)
     */
    @Override
    public STRATEGYSTAGETYPE withClassPathResolution(boolean useClassPathResolution) {
        if (!useClassPathResolution) {
            this.session.disableClassPathWorkspaceReader();
        }
        return this.covarientReturn();
    }

    /**
     * {@inheritDoc}
     *
     * @see org.jboss.shrinkwrap.resolver.api.maven.MavenStrategyStageBase#withMavenCentralRepo(boolean)
     */
    @Override
    public STRATEGYSTAGETYPE withMavenCentralRepo(boolean useMavenCentral) {
        if (!useMavenCentral) {
            this.session.disableMavenCentral();
        }
        return this.covarientReturn();
    }

    private STRATEGYSTAGETYPE covarientReturn() {
        return this.getActualClass().cast(this);
    }

    protected abstract Class<STRATEGYSTAGETYPE> getActualClass();

    /**
     * Creates a new {@link MavenFormatStage} instance for the current {@link MavenWorkingSession}
     *
     * @param filteredArtifacts Required
     * @return
     */
    protected abstract FORMATSTAGETYPE createFormatStage(final Collection<MavenResolvedArtifact> filteredArtifacts);

}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.repository.migration;

import org.apache.atlas.model.migration.MigrationImportStatus;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.graph.AtlasGraphProvider;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.repository.graphdb.AtlasIndexQuery;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Iterator;

import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.getEncodedProperty;
import static org.apache.atlas.repository.store.graph.v2.AtlasGraphUtilsV2.setEncodedProperty;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.encodePropertyKey;
import static org.apache.atlas.type.Constants.INTERNAL_PROPERTY_KEY_PREFIX;

public class DataMigrationStatusService {
    private static final Logger LOG = LoggerFactory.getLogger(DataMigrationStatusService.class);
    private final MigrationStatusVertexManagement migrationStatusVertexManagement;

    private MigrationImportStatus status;

    public DataMigrationStatusService() {
        this.migrationStatusVertexManagement = new MigrationStatusVertexManagement(AtlasGraphProvider.getGraphInstance());
    }

    public DataMigrationStatusService(AtlasGraph atlasGraph) {
        this.migrationStatusVertexManagement = new MigrationStatusVertexManagement(atlasGraph);
    }


    public void init(String fileToImport) {
        this.status = new MigrationImportStatus(fileToImport);
        if (!this.migrationStatusVertexManagement.exists(fileToImport)) {
            return;
        }

        getCreate(fileToImport);
    }

    public MigrationImportStatus getCreate(String fileName) {
        return getCreate(new MigrationImportStatus(fileName));
    }

    public MigrationImportStatus getCreate(MigrationImportStatus status) {
        try {
            this.status = this.migrationStatusVertexManagement.createOrUpdate(status);
        } catch (Exception ex) {
            LOG.error("DataMigrationStatusService: Setting status: {}: Resulted in error!", status.getName(), ex);
        }

        return this.status;
    }

    public MigrationImportStatus getStatus() {
        if (this.status != null &&
                StringUtils.isEmpty(this.status.getOperationStatus()) &&
                this.migrationStatusVertexManagement.exists(this.status.getName())) {
            return getCreate(this.status);
        } else {
            return this.status;
        }
    }

    public MigrationImportStatus getByName(String name) {
        return this.migrationStatusVertexManagement.findByName(name);
    }

    public void delete() {
        if (this.status == null) {
            return;
        }

        MigrationImportStatus status = getByName(this.status.getName());
        this.migrationStatusVertexManagement.delete(status.getName());
        this.status = null;
    }

    public void savePosition(String position) {
        this.status.setCurrentIndex(Long.valueOf(position));
        this.migrationStatusVertexManagement.updateVertexPartial(this.status);
    }

    public void setStatus(String status) {
        this.status.setOperationStatus(status);
        this.migrationStatusVertexManagement.updateVertexPartial(this.status);
    }

    private static class MigrationStatusVertexManagement {
        public static final String PROPERTY_KEY_START_TIME = encodePropertyKey(INTERNAL_PROPERTY_KEY_PREFIX + "migration.startTime");
        public static final String PROPERTY_KEY_SIZE = encodePropertyKey(INTERNAL_PROPERTY_KEY_PREFIX + "migration.size");
        public static final String PROPERTY_KEY_POSITION = encodePropertyKey(INTERNAL_PROPERTY_KEY_PREFIX + "migration.position");
        public static final String PROPERTY_KEY_STATUS = encodePropertyKey(INTERNAL_PROPERTY_KEY_PREFIX + "migration.status");

        private AtlasGraph atlasGraph;
        private AtlasVertex vertex;

        public MigrationStatusVertexManagement(AtlasGraph atlasGraph) {
            this.atlasGraph = atlasGraph;
        }

        public MigrationImportStatus createOrUpdate(MigrationImportStatus status) {
            this.vertex = findByNameInternal(status.getName());

            if (this.vertex == null) {
                this.vertex = atlasGraph.addVertex();
                LOG.info("MigrationStatusVertexManagement: Vertex created!");
                updateVertex(this.vertex, status);
            }

            return to(this.vertex);
        }

        public boolean exists(String name) {
            return findByNameInternal(name) != null;
        }

        public MigrationImportStatus findByName(String name) {
            if (this.vertex != null) {
                return to(this.vertex);
            }

            AtlasVertex v = findByNameInternal(name);
            if (v == null) {
                return null;
            }

            this.vertex = v;
            LOG.info("MigrationImportStatus: Vertex found!");
            return to(v);
        }

        public void delete(String name) {
            try {
                AtlasVertex vertex = findByNameInternal(name);
                atlasGraph.removeVertex(vertex);
                this.vertex = null;
            } finally {
                atlasGraph.commit();
            }
        }

        private AtlasVertex findByNameInternal(String name) {
            try {
                String idxQueryString = String.format("%s\"%s\":\"%s\"", AtlasGraphUtilsV2.getIndexSearchPrefix(), Constants.GUID_PROPERTY_KEY, name);
                AtlasIndexQuery idxQuery = atlasGraph.indexQuery(Constants.VERTEX_INDEX, idxQueryString);
                Iterator<AtlasIndexQuery.Result<Object, Object>> results = idxQuery.vertices();

                AtlasIndexQuery.Result<?, ?> qryResult = results.hasNext() ? results.next() : null;
                if (qryResult != null) {
                    return qryResult.getVertex();
                } else {
                    return null;
                }
            } catch (Exception e) {
                LOG.error("MigrationStatusVertexManagement.findByNameInternal: Failed!", e);
            } finally {
                atlasGraph.commit();
            }

            return null;
        }

        public void updateVertexPartial(MigrationImportStatus status) {
            try {
                setEncodedProperty(vertex, PROPERTY_KEY_POSITION, status.getCurrentIndex());
            } catch (Exception e) {
                LOG.warn("Error updating status. Please rely on log messages.", e);
            } finally {
                atlasGraph.commit();
            }
        }

        private void updateVertex(AtlasVertex vertex, MigrationImportStatus status) {
            try {
                setEncodedProperty(vertex, Constants.GUID_PROPERTY_KEY, status.getName());

                setEncodedProperty(vertex, PROPERTY_KEY_START_TIME,
                        (status.getStartTime() != null)
                                ? status.getStartTime().getTime()
                                : System.currentTimeMillis());

                setEncodedProperty(vertex, PROPERTY_KEY_SIZE, status.getTotalCount());
                setEncodedProperty(vertex, PROPERTY_KEY_POSITION, status.getCurrentIndex());
                setEncodedProperty(vertex, PROPERTY_KEY_STATUS, status.getOperationStatus());
            } catch (Exception ex) {
                LOG.error("Error updating MigrationImportStatus vertex. Status may not be persisted correctly.", ex);
            } finally {
                atlasGraph.commit();
            }
        }

        private static MigrationImportStatus to(AtlasVertex vertex) {
            MigrationImportStatus ret = new MigrationImportStatus();

            try {
                ret.setName(getEncodedProperty(vertex, Constants.GUID_PROPERTY_KEY, String.class));

                Long dateValue = getEncodedProperty(vertex, PROPERTY_KEY_START_TIME, Long.class);
                if (dateValue != null) {
                    ret.setStartTime(new Date(dateValue));
                }

                Long size = getEncodedProperty(vertex, PROPERTY_KEY_SIZE, Long.class);
                if (size != null) {
                    ret.setTotalCount(size);
                }

                Long position = getEncodedProperty(vertex, PROPERTY_KEY_POSITION, Long.class);
                if (position != null) {
                    ret.setCurrentIndex(position);
                }

                ret.setOperationStatus(getEncodedProperty(vertex, PROPERTY_KEY_STATUS, String.class));
            } catch (Exception ex) {
                LOG.error("Error converting to MigrationImportStatus. Will proceed with default values.", ex);
            }

            return ret;
        }
    }
}

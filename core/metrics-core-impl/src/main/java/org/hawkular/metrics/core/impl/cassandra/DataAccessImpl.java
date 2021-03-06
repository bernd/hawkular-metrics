/*
 * Copyright 2014-2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.core.impl.cassandra;

import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TupleType;
import com.datastax.driver.core.TupleValue;
import com.datastax.driver.core.UDTValue;
import com.datastax.driver.core.UserType;
import com.datastax.driver.core.utils.UUIDs;

import org.hawkular.metrics.core.api.AggregationTemplate;
import org.hawkular.metrics.core.api.Availability;
import org.hawkular.metrics.core.api.AvailabilityMetric;
import org.hawkular.metrics.core.api.Counter;
import org.hawkular.metrics.core.api.Interval;
import org.hawkular.metrics.core.api.Metric;
import org.hawkular.metrics.core.api.MetricData;
import org.hawkular.metrics.core.api.MetricId;
import org.hawkular.metrics.core.api.MetricType;
import org.hawkular.metrics.core.api.NumericData;
import org.hawkular.metrics.core.api.NumericMetric;
import org.hawkular.metrics.core.api.Retention;
import org.hawkular.metrics.core.api.RetentionSettings;
import org.hawkular.metrics.core.api.Tenant;
import org.hawkular.metrics.core.api.TimeUUIDUtils;

/**
 *
 * @author John Sanda
 */
public class DataAccessImpl implements DataAccess {

    private Session session;

    private PreparedStatement insertTenant;

    private PreparedStatement findAllTenantIds;

    private PreparedStatement findTenant;

    private PreparedStatement insertIntoMetricsIndex;

    private PreparedStatement findMetric;

    private PreparedStatement addMetricTagsToDataTable;

    private PreparedStatement addMetadataAndDataRetention;

    private PreparedStatement deleteMetricTagsFromDataTable;

    private PreparedStatement insertNumericData;

    private PreparedStatement findNumericDataByDateRangeExclusive;

    private PreparedStatement findNumericDataWithWriteTimeByDateRangeExclusive;

    private PreparedStatement findNumericDataByDateRangeInclusive;

    private PreparedStatement findNumericDataWithWriteTimeByDateRangeInclusive;

    private PreparedStatement findAvailabilityByDateRangeInclusive;

    private PreparedStatement deleteNumericMetric;

    private PreparedStatement findNumericMetrics;

    private PreparedStatement updateCounter;

    private PreparedStatement findCountersByGroup;

    private PreparedStatement findCountersByGroupAndName;

    private PreparedStatement insertNumericTags;

    private PreparedStatement insertAvailabilityTags;

    private PreparedStatement updateDataWithTags;

    private PreparedStatement findNumericDataByTag;

    private PreparedStatement findAvailabilityByTag;

    private PreparedStatement insertAvailability;

    private PreparedStatement findAvailabilities;

    private PreparedStatement updateMetricsIndex;

    private PreparedStatement addTagsToMetricsIndex;

    private PreparedStatement deleteTagsFromMetricsIndex;

    private PreparedStatement readMetricsIndex;

    private PreparedStatement findAvailabilitiesWithWriteTime;

    private PreparedStatement updateRetentionsIndex;

    private PreparedStatement findDataRetentions;

    private PreparedStatement insertMetricsTagsIndex;

    private PreparedStatement deleteMetricsTagsIndex;

    private PreparedStatement findMetricsByTagName;

    public DataAccessImpl(Session session) {
        this.session = session;
        initPreparedStatements();
    }

    protected void initPreparedStatements() {
        insertTenant = session.prepare(
            "INSERT INTO tenants (id, retentions, aggregation_templates) " +
            "VALUES (?, ?, ?) " +
            "IF NOT EXISTS");

        findAllTenantIds = session.prepare("SELECT DISTINCT id FROM tenants");

        findTenant = session.prepare("SELECT id, retentions, aggregation_templates FROM tenants WHERE id = ?");

        findMetric = session.prepare(
            "SELECT tenant_id, type, metric, interval, dpart, m_tags, data_retention " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ?");

        addMetricTagsToDataTable = session.prepare(
            "UPDATE data " +
            "SET m_tags = m_tags + ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ?");

        addMetadataAndDataRetention = session.prepare(
            "UPDATE data " +
            "SET m_tags = m_tags + ?, data_retention = ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ?");

        deleteMetricTagsFromDataTable = session.prepare(
            "UPDATE data " +
            "SET m_tags = m_tags - ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ?");

        insertIntoMetricsIndex = session.prepare(
            "INSERT INTO metrics_idx (tenant_id, type, interval, metric, data_retention, tags) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "IF NOT EXISTS");

        updateMetricsIndex = session.prepare(
            "INSERT INTO metrics_idx (tenant_id, type, interval, metric) VALUES (?, ?, ?, ?)");

        addTagsToMetricsIndex = session.prepare(
            "UPDATE metrics_idx " +
            "SET tags = tags + ? " +
            "WHERE tenant_id = ? AND type = ? AND interval = ? AND metric = ?");

        deleteTagsFromMetricsIndex = session.prepare(
            "UPDATE metrics_idx " +
            "SET tags = tags - ?" +
            "WHERE tenant_id = ? AND type = ? AND interval = ? AND metric = ?");

        readMetricsIndex = session.prepare(
            "SELECT metric, interval, tags, data_retention " +
            "FROM metrics_idx " +
            "WHERE tenant_id = ? AND type = ?");

        insertNumericData = session.prepare(
            "UPDATE data " +
            "USING TTL ?" +
            "SET m_tags = m_tags + ?, n_value = ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time = ? ");

        findNumericDataByDateRangeExclusive = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, m_tags, data_retention, n_value, tags " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time >= ?"
                + " AND time < ?");

        findNumericDataWithWriteTimeByDateRangeExclusive = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, m_tags, data_retention, n_value, tags,"
                + " WRITETIME(n_value) " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time >= ?"
                + " AND time < ?");

        findNumericDataByDateRangeInclusive = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, m_tags, data_retention, n_value, tags " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time >= ?"
                + " AND time <= ?");

        findNumericDataWithWriteTimeByDateRangeInclusive = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, m_tags, data_retention, n_value, tags,"
                + " WRITETIME(n_value) " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time >= ?"
                + " AND time <= ?");

        findAvailabilityByDateRangeInclusive = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, m_tags, data_retention, availability, tags,"
                + " WRITETIME(availability) " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time >= ?"
                + " AND time <= ?");

        deleteNumericMetric = session.prepare(
            "DELETE FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ?");

        findNumericMetrics = session.prepare(
            "SELECT DISTINCT tenant_id, type, metric, interval, dpart FROM data;");

        updateCounter = session.prepare(
            "UPDATE counters " +
            "SET c_value = c_value + ? " +
            "WHERE tenant_id = ? AND group = ? AND c_name = ?");

        findCountersByGroup = session.prepare(
            "SELECT tenant_id, group, c_name, c_value FROM counters WHERE tenant_id = ? AND group = ?");

        findCountersByGroupAndName = session.prepare(
            "SELECT tenant_id, group, c_name, c_value FROM counters WHERE tenant_id = ? AND group = ? AND c_name IN ?");

        insertNumericTags = session.prepare(
            "INSERT INTO tags (tenant_id, tname, tvalue, type, metric, interval, time, n_value) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "USING TTL ?");

        insertAvailabilityTags = session.prepare(
            "INSERT INTO tags (tenant_id, tname, tvalue, type, metric, interval, time, availability) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "USING TTL ?");

        updateDataWithTags = session.prepare(
            "UPDATE data " +
            "SET tags = tags + ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time = ?");

        findNumericDataByTag = session.prepare(
            "SELECT tenant_id, tname, tvalue, type, metric, interval, time, n_value " +
            "FROM tags " +
            "WHERE tenant_id = ? AND tname = ? AND tvalue = ?");

        findAvailabilityByTag = session.prepare(
            "SELECT tenant_id, tname, tvalue, type, metric, interval, time, availability " +
            "FROM tags " +
            "WHERE tenant_id = ? AND tname = ? AND tvalue = ?");

        insertAvailability = session.prepare(
            "UPDATE data " +
            "USING TTL ? " +
            "SET m_tags = m_tags + ?, availability = ? " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time = ?");

        findAvailabilities = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, m_tags, data_retention, availability, tags " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time >= ?"
                + " AND time < ?");

        findAvailabilitiesWithWriteTime = session.prepare(
            "SELECT tenant_id, metric, interval, dpart, time, m_tags, data_retention, availability, tags,"
                + " WRITETIME(availability) " +
            "FROM data " +
            "WHERE tenant_id = ? AND type = ? AND metric = ? AND interval = ? AND dpart = ? AND time >= ?"
                + " AND time < ?");

        updateRetentionsIndex = session.prepare(
            "INSERT INTO retentions_idx (tenant_id, type, interval, metric, retention) VALUES (?, ?, ?, ?, ?)");

        findDataRetentions = session.prepare(
            "SELECT tenant_id, type, interval, metric, retention " +
            "FROM retentions_idx " +
            "WHERE tenant_id = ? AND type = ?");

        insertMetricsTagsIndex = session.prepare(
            "INSERT INTO metrics_tags_idx (tenant_id, tname, tvalue, type, metric, interval) VALUES " +
            "(?, ?, ?, ?, ?, ?)");

        deleteMetricsTagsIndex = session.prepare(
            "DELETE FROM metrics_tags_idx " +
            "WHERE tenant_id = ? AND tname = ? AND tvalue = ? AND type = ? AND metric = ? AND interval = ?");

        findMetricsByTagName = session.prepare(
            "SELECT tvalue, type, metric, interval " +
            "FROM metrics_tags_idx " +
            "WHERE tenant_id = ? AND tname = ?");
    }

    @Override
    public ResultSetFuture insertTenant(Tenant tenant) {
        UserType aggregationTemplateType = getKeyspace().getUserType("aggregation_template");
        List<UDTValue> templateValues = new ArrayList<>(tenant.getAggregationTemplates().size());
        for (AggregationTemplate template : tenant.getAggregationTemplates()) {
            UDTValue value = aggregationTemplateType.newValue();
            value.setInt("type", template.getType().getCode());
            value.setString("interval", template.getInterval().toString());
            value.setSet("fns", template.getFunctions());
            templateValues.add(value);
        }

        Map<TupleValue, Integer> retentions = new HashMap<>();
        for (RetentionSettings.RetentionKey key : tenant.getRetentionSettings().keySet()) {
            TupleType metricType = TupleType.of(DataType.cint(), DataType.text());
            TupleValue tuple = metricType.newValue();
            tuple.setInt(0, key.metricType.getCode());
            if (key.interval == null) {
                tuple.setString(1, null);
            } else {
                tuple.setString(1, key.interval.toString());
            }
            retentions.put(tuple, tenant.getRetentionSettings().get(key));
        }

        return session.executeAsync(insertTenant.bind(tenant.getId(), retentions, templateValues));
    }

    @Override
    public ResultSetFuture findAllTenantIds() {
        return session.executeAsync(findAllTenantIds.bind());
    }

    @Override
    public ResultSetFuture findTenant(String id) {
        return session.executeAsync(findTenant.bind(id));
    }

    @Override
    public ResultSetFuture insertMetricInMetricsIndex(Metric metric) {
        return session.executeAsync(insertIntoMetricsIndex.bind(metric.getTenantId(), metric.getType().getCode(),
            metric.getId().getInterval().toString(), metric.getId().getName(), metric.getDataRetention(),
            getTags(metric)));
    }

    private Map<String, String> getTags(Metric<? extends MetricData> metric) {
        return metric.getTags().entrySet().stream().collect(toMap(Map.Entry::getKey, e -> e.getValue().orElse("")));
    }

    @Override
    public ResultSetFuture findMetric(String tenantId, MetricType type, MetricId id, long dpart) {
        return session.executeAsync(findMetric.bind(tenantId, type.getCode(), id.getName(),
            id.getInterval().toString(), dpart));
    }

    // This method updates the metric tags and data retention in the data table. In the
    // long term after we add support for bucketing/date partitioning I am not sure that we
    // will store metric tags and data retention in the data table. We would have to
    // determine when we start writing data to a new partition, e.g., the start of the next
    // day, and then add the tags and retention to the new partition.
    @Override
    public ResultSetFuture addTagsAndDataRetention(Metric metric) {
        return session.executeAsync(addMetadataAndDataRetention.bind(getTags(metric), metric.getDataRetention(),
            metric.getTenantId(), metric.getType().getCode(), metric.getId().getName(),
            metric.getId().getInterval().toString(), metric.getDpart()));
    }

    @Override
    public ResultSetFuture addTags(Metric metric, Map<String, String> tags) {
        BatchStatement batch = new BatchStatement(BatchStatement.Type.UNLOGGED);
        batch.add(addMetricTagsToDataTable.bind(tags, metric.getTenantId(), metric.getType().getCode(),
            metric.getId().getName(), metric.getId().getInterval().toString(), metric.getDpart()));
        batch.add(addTagsToMetricsIndex.bind(tags, metric.getTenantId(), metric.getType().getCode(),
            metric.getId().getInterval().toString(), metric.getId().getName()));
        return session.executeAsync(batch);
    }

    @Override
    public ResultSetFuture deleteTags(Metric metric, Set<String> tags) {
        BatchStatement batch = new BatchStatement(BatchStatement.Type.UNLOGGED);
        batch.add(deleteMetricTagsFromDataTable.bind(tags, metric.getTenantId(), metric.getType().getCode(),
            metric.getId().getName(), metric.getId().getInterval().toString(), metric.getDpart()));
        batch.add(deleteTagsFromMetricsIndex.bind(tags, metric.getTenantId(), metric.getType().getCode(),
            metric.getId().getInterval().toString(), metric.getId().getName()));
        return session.executeAsync(batch);
    }

    @Override
    public ResultSetFuture updateTagsInMetricsIndex(Metric metric, Map<String, String> additions,
        Set<String> deletions) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED)
            .add(addTagsToMetricsIndex.bind(additions, metric.getTenantId(),
                metric.getType().getCode(), metric.getId().getInterval().toString(), metric.getId().getName()))
            .add(deleteTagsFromMetricsIndex.bind(deletions, metric.getTenantId(), metric.getType().getCode(),
                metric.getId().getInterval().toString(), metric.getId().getName()));
        return session.executeAsync(batchStatement);
    }

    @Override
    public <T extends Metric> ResultSetFuture updateMetricsIndex(List<T> metrics) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED);
        for (T metric : metrics) {
            batchStatement.add(updateMetricsIndex.bind(metric.getTenantId(), metric.getType().getCode(),
                metric.getId().getInterval().toString(), metric.getId().getName()));
        }
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture findMetricsInMetricsIndex(String tenantId, MetricType type) {
        return session.executeAsync(readMetricsIndex.bind(tenantId, type.getCode()));
    }

    @Override
    public ResultSetFuture insertData(NumericMetric metric, int ttl) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED);
        for (NumericData d : metric.getData()) {
            batchStatement.add(insertNumericData.bind(ttl, getTags(metric), d.getValue(), metric.getTenantId(),
                metric.getType().getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
                metric.getDpart(), d.getTimeUUID()));
        }
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture findData(NumericMetric metric, long startTime, long endTime) {
        return findData(metric, startTime, endTime, false);
    }

    @Override
    public ResultSetFuture findData(NumericMetric metric, long startTime, long endTime, boolean includeWriteTime) {
        if (includeWriteTime) {
            return session.executeAsync(findNumericDataWithWriteTimeByDateRangeExclusive.bind(metric.getTenantId(),
                MetricType.NUMERIC.getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
                metric.getDpart(), TimeUUIDUtils.getTimeUUID(startTime), TimeUUIDUtils.getTimeUUID(endTime)));
        } else {
            return session.executeAsync(findNumericDataByDateRangeExclusive.bind(metric.getTenantId(),
                MetricType.NUMERIC.getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
                metric.getDpart(), TimeUUIDUtils.getTimeUUID(startTime), TimeUUIDUtils.getTimeUUID(endTime)));
        }
    }

    @Override
    public ResultSetFuture findData(NumericMetric metric, long timestamp, boolean includeWriteTime) {
        if (includeWriteTime) {
            return session.executeAsync(findNumericDataWithWriteTimeByDateRangeInclusive.bind(metric.getTenantId(),
                MetricType.NUMERIC.getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
                metric.getDpart(), UUIDs.startOf(timestamp), UUIDs.endOf(timestamp)));
        } else {
            return session.executeAsync(findNumericDataByDateRangeInclusive.bind(metric.getTenantId(),
                MetricType.NUMERIC.getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
                metric.getDpart(), UUIDs.startOf(timestamp), UUIDs.endOf(timestamp)));
        }
    }

    @Override
    public ResultSetFuture findData(AvailabilityMetric metric, long startTime, long endTime) {
        return findData(metric, startTime, endTime, false);
    }

    @Override
    public ResultSetFuture findData(AvailabilityMetric metric, long startTime, long endTime, boolean includeWriteTime) {
        if (includeWriteTime) {
            return session.executeAsync(findAvailabilitiesWithWriteTime.bind(metric.getTenantId(),
                MetricType.AVAILABILITY.getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
                metric.getDpart(), TimeUUIDUtils.getTimeUUID(startTime), TimeUUIDUtils.getTimeUUID(endTime)));
        } else {
            return session.executeAsync(findAvailabilities.bind(metric.getTenantId(), MetricType.AVAILABILITY.getCode(),
                metric.getId().getName(), metric.getId().getInterval().toString(), metric.getDpart(),
                TimeUUIDUtils.getTimeUUID(startTime), TimeUUIDUtils.getTimeUUID(endTime)));
        }
    }

    @Override
    public ResultSetFuture findData(AvailabilityMetric metric, long timestamp) {
        return session.executeAsync(findAvailabilityByDateRangeInclusive.bind(metric.getTenantId(),
            MetricType.AVAILABILITY.getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
            metric.getDpart(), UUIDs.startOf(timestamp), UUIDs.endOf(timestamp)));
    }

    @Override
    public ResultSetFuture deleteNumericMetric(String tenantId, String metric, Interval interval, long dpart) {
        return session.executeAsync(deleteNumericMetric.bind(tenantId, MetricType.NUMERIC.getCode(), metric,
            interval.toString(), dpart));
    }

    @Override
    public ResultSetFuture findAllNumericMetrics() {
        return session.executeAsync(findNumericMetrics.bind());
    }

    @Override
    public ResultSetFuture insertNumericTag(String tag, String tagValue, List<NumericData> data) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED);
        for (NumericData d : data) {
            batchStatement.add(insertNumericTags.bind(d.getMetric().getTenantId(), tag, tagValue,
                MetricType.NUMERIC.getCode(), d.getMetric().getId().getName(),
                d.getMetric().getId().getInterval().toString(), d.getTimeUUID(), d.getValue(), d.getTTL()));
        }
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture insertAvailabilityTag(String tag, String tagValue, List<Availability> data) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED);
        for (Availability a : data) {
            batchStatement.add(insertAvailabilityTags.bind(a.getMetric().getTenantId(), tag, tagValue,
                MetricType.AVAILABILITY.getCode(), a.getMetric().getId().getName(),
                a.getMetric().getId().getInterval().toString(), a.getTimeUUID(), a.getBytes(), a.getTTL()));
        }
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture updateDataWithTag(MetricData data, Map<String, String> tags) {
        return session.executeAsync(updateDataWithTags.bind(tags, data.getMetric().getTenantId(),
            data.getMetric().getType().getCode(), data.getMetric().getId().getName(),
            data.getMetric().getId().getInterval().toString(), data.getMetric().getDpart(), data.getTimeUUID()));
    }

    @Override
    public ResultSetFuture findNumericDataByTag(String tenantId, String tag, String tagValue) {
        return session.executeAsync(findNumericDataByTag.bind(tenantId, tag, tagValue));
    }

    @Override
    public ResultSetFuture findAvailabilityByTag(String tenantId, String tag, String tagValue) {
        return session.executeAsync(findAvailabilityByTag.bind(tenantId, tag, tagValue));
    }

    @Override
    public ResultSetFuture insertData(AvailabilityMetric metric, int ttl) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED);
        for (Availability a : metric.getData()) {
            batchStatement.add(insertAvailability.bind(ttl, metric.getTags(), a.getBytes(), metric.getTenantId(),
                metric.getType().getCode(), metric.getId().getName(), metric.getId().getInterval().toString(),
                metric.getDpart(), a.getTimeUUID()));
        }
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture findAvailabilityData(AvailabilityMetric metric, long startTime, long endTime) {
        return session.executeAsync(findAvailabilities.bind(metric.getTenantId(), MetricType.AVAILABILITY.getCode(),
            metric.getId().getName(), metric.getId().getInterval().toString(), metric.getDpart(),
            TimeUUIDUtils.getTimeUUID(startTime), TimeUUIDUtils.getTimeUUID(endTime)));
    }

    @Override
    public ResultSetFuture updateCounter(Counter counter) {
        BoundStatement statement = updateCounter.bind(counter.getValue(), counter.getTenantId(), counter.getGroup(),
            counter.getName());
        return session.executeAsync(statement);
    }

    @Override
    public ResultSetFuture updateCounters(Collection<Counter> counters) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.COUNTER);
        for (Counter counter : counters) {
            batchStatement.add(updateCounter.bind(counter.getValue(), counter.getTenantId(), counter.getGroup(),
                counter.getName()));
        }
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture findDataRetentions(String tenantId, MetricType type) {
        return session.executeAsync(findDataRetentions.bind(tenantId, type.getCode()));
    }

    @Override
    public ResultSetFuture updateRetentionsIndex(String tenantId, MetricType type, Set<Retention> retentions) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED);
        for (Retention r : retentions) {
            batchStatement.add(updateRetentionsIndex.bind(tenantId, type.getCode(), r.getId().getInterval().toString(),
                r.getId().getName(), r.getValue()));
        }
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture insertIntoMetricsTagsIndex(Metric metric, Map<String, String> tags) {
        return executeTagsBatch(tags, (name, value) -> insertMetricsTagsIndex.bind(metric.getTenantId(), name, value,
            metric.getType().getCode(), metric.getId().getName(), metric.getId().getInterval().toString()));
    }

    @Override
    public ResultSetFuture deleteFromMetricsTagsIndex(Metric metric, Map<String, String> tags) {
        return executeTagsBatch(tags, (name, value) -> deleteMetricsTagsIndex.bind(metric.getTenantId(), name, value,
            metric.getType().getCode(), metric.getId().getName(), metric.getId().getInterval().toString()));
    }

    private ResultSetFuture executeTagsBatch(Map<String, String> tags,
        BiFunction<String, String, BoundStatement> bindVars) {
        BatchStatement batchStatement = new BatchStatement(BatchStatement.Type.UNLOGGED);
        tags.entrySet().stream().forEach(entry -> batchStatement.add(bindVars.apply(entry.getKey(), entry.getValue())));
        return session.executeAsync(batchStatement);
    }

    @Override
    public ResultSetFuture findMetricsByTag(String tenantId, String tag) {
        return session.executeAsync(findMetricsByTagName.bind(tenantId, tag));
    }

    @Override
    public ResultSetFuture updateRetentionsIndex(Metric metric) {
        return session.executeAsync(updateRetentionsIndex.bind(metric.getTenantId(), metric.getType().getCode(),
            metric.getId().getInterval().toString(), metric.getId().getName(), metric.getDataRetention()));
    }

    public ResultSetFuture findCounters(String tenantId, String group) {
        BoundStatement statement = findCountersByGroup.bind(tenantId, group);
        return session.executeAsync(statement);
    }

    public ResultSetFuture findCounters(String tenantId, String group, List<String> names) {
        BoundStatement statement = findCountersByGroupAndName.bind(tenantId, group, names);
        return session.executeAsync(statement);
    }

    private KeyspaceMetadata getKeyspace() {
        return session.getCluster().getMetadata().getKeyspace(session.getLoggedKeyspace());
    }

}

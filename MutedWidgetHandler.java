package com.browserstack.observability.handlers;

import static com.browserstack.observability.util.Constants.SEARCH_INDEX;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.browserstack.observability.dao.ProjectService;
import com.browserstack.observability.models.ProjectLite;
import com.browserstack.observability.models.Widgets;
import com.browserstack.observability.models.response.Widgets.Insights;
import com.browserstack.observability.models.response.Widgets.drilldown.BaseDrillDownDataPoint;
import com.browserstack.observability.models.response.Widgets.drilldown.DrillDownData;
import com.browserstack.observability.models.response.Widgets.drilldown.PercentageDrillDownDataPoint;
import com.browserstack.observability.util.ESQueries;
import com.browserstack.observability.util.UserManager;
import com.browserstack.observability.util.WidgetUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import testhub.ApiException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MutedWidgetHandler implements WidgetHandler {

    private static final DecimalFormat decfor = new DecimalFormat("0.00");

    @Autowired
    private ProjectService projectService;

    @Override
    public Object getWidget(
            ElasticsearchClient elasticsearchClient, Widgets widgetRequest, Long lowerBound, Long upperBound)
            throws IOException {
        throw new UnsupportedEncodingException();
    }

    @Override
    public DrillDownData getWidgetDrillDown(
            ElasticsearchClient elasticsearchClient, Widgets widgetRequest, Long lowerBound, Long upperBound)
            throws IOException, ApiException {

        Boolean isConflictingFilter = isOverlapping(
                widgetRequest.getDashboardFilters(),
                widgetRequest.getConfig().getFilters().getValue());

        if (isConflictingFilter) {
            return DrillDownData.builder().isConflicting(true).build();
        }

        SearchResponse<JsonNode> aggregationResponse = WidgetUtil.makeRequest(
                elasticsearchClient,
                SEARCH_INDEX,
                generateBaseQuery(UserManager.getGroupId(), upperBound, lowerBound, widgetRequest),
                getDrillDownAggregates(),
                0);

        long isMutedSummary =
                aggregationResponse.aggregations().get("muted").filter().docCount();
        long totalSummary = aggregationResponse.hits().total().value();
        Double rateSummary = ((double) isMutedSummary / (double) totalSummary) * 100f;
        if (rateSummary.isNaN() || rateSummary.isInfinite()) rateSummary = (double) 0;
        String meta = String.format("(%d/%d)", isMutedSummary, totalSummary);
        Insights insights = Insights.builder()
                .value(decfor.format(rateSummary) + "%")
                .meta(meta)
                .build();


        Set<Long> thProjectIds = aggregationResponse.aggregations().get("thProjectId").lterms().buckets().array().stream()
                .map(bucket -> bucket.key())
                .collect(Collectors.toSet());
        Map<Long, ProjectLite> projectLiteMap = projectService.fetchProjectLitesInBatches(thProjectIds);

        List<BaseDrillDownDataPoint> dataPointList =
                aggregationResponse.aggregations().get("projectId").lterms().buckets().array().stream()
                        .map(longTermsBucket -> {
                            long total = longTermsBucket.docCount();
                            long isMuted = longTermsBucket
                                    .aggregations()
                                    .get("muted")
                                    .filter()
                                    .docCount();
                            if (isMuted == 0) return null;
                            Double rate = ((double) isMuted / (double) total) * 100f;
                            if (rate.isNaN() || rate.isInfinite()) rate = (double) 0;
                            if (Objects.isNull(projectLiteMap.get(longTermsBucket.key()))) return null;
                            String projectName = Optional.ofNullable(projectLiteMap.get(longTermsBucket.key()))
                                    .map(ProjectLite::getName)
                                    .orElse(null);
                            String normalisedName = Optional.ofNullable(projectLiteMap.get(longTermsBucket.key()))
                                    .map(ProjectLite::getNormalisedName)
                                    .orElse(null);
                            Long thProjectId = Optional.ofNullable(projectLiteMap.get(longTermsBucket.key()))
                                    .map(ProjectLite::getThProjectId)
                                    .orElse(null);
                            return PercentageDrillDownDataPoint.builder()
                                    .projectId(longTermsBucket.key())
                                    .thProjectId(thProjectId)
                                    .value(Double.valueOf(decfor.format(rate)))
                                    .num(isMuted)
                                    .den(total)
                                    .projectName(projectName)
                                    .normalisedName(normalisedName)
                                    .build();
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        return DrillDownData.builder().insights(insights).data(dataPointList).build();
    }

    private Map<String, Aggregation> getDrillDownAggregates() {
        Map<String, Aggregation> topLevelAggregates = new HashMap<>();
        Map<String, Aggregation> rootAggregates = new HashMap<>();
        topLevelAggregates.put("muted", ESQueries.generateFilterAggregation(ESQueries.muted));
        rootAggregates.put("projectId", ESQueries.generateTermsAggregation("projectId", topLevelAggregates, 10000));
        rootAggregates.put("muted", ESQueries.generateFilterAggregation(ESQueries.muted));
        rootAggregates.put("thProjectId", ESQueries.generateTermsAggregation("thProjectId", topLevelAggregates, 10000));

        return rootAggregates;
    }

    private Query generateBaseQuery(Long groupId, Long upperBound, Long lowerBound, Widgets widgets) {
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(m -> m.term(t -> t.field("groupId").value(groupId)));
        boolBuilder.filter(ESQueries.withinDateRange(lowerBound, upperBound));
        if (Objects.nonNull(widgets.getConfig().getFilters())) {
            Boolean includeReRuns =
                    Objects.nonNull(widgets.getConfig().getFilters().getValue().getIncludeReRuns())
                            && widgets.getConfig()
                                    .getFilters()
                                    .getValue()
                                    .getIncludeReRuns()
                                    .equals(Boolean.TRUE);
            WidgetUtil.applyFilter(
                    boolBuilder,
                    widgets.getDashboardFilters(),
                    widgets.getConfig().getFilters(),
                    Boolean.TRUE,
                    includeReRuns);
        }
        return boolBuilder.build()._toQuery();
    }
}

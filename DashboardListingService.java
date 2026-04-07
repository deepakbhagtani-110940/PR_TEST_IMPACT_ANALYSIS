package com.browserstack.observability.services;

import com.browserstack.observability.dao.DashboardProjectRepository;
import com.browserstack.observability.dao.DashboardRepository;
import com.browserstack.observability.dao.ProjectRepository;
import com.browserstack.observability.dao.ReportScheduleRepository;
import com.browserstack.observability.enums.FrequencyFilter;
import com.browserstack.observability.enums.Source;
import com.browserstack.observability.models.Dashboard;
import com.browserstack.observability.models.DashboardProject;
import com.browserstack.observability.models.DashboardType;
import com.browserstack.observability.models.FilterValuesV2;
import com.browserstack.observability.models.Projects;
import com.browserstack.observability.models.ReportSchedule;
import com.browserstack.observability.models.cache.UserCacheData;
import com.browserstack.observability.models.request.DashboardListRequest;
import com.browserstack.observability.models.request.WidgetEnhancedFilterResp;
import com.browserstack.observability.models.response.DashboardListResponse;
import com.browserstack.observability.models.response.DashboardListResponse.OwnerInfo;
import com.browserstack.observability.models.response.DashboardListResponse.ProjectInfo;
import com.browserstack.observability.models.response.DashboardListResponse.ReportSummary;
import com.browserstack.observability.service.DashboardService;
import com.browserstack.observability.service.HomeService;
import com.browserstack.observability.util.SourceUtil;
import com.browserstack.observability.util.UserManager;
import com.browserstack.observability.util.redis.repository.RedisDao;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import testhub.ApiException;
import testhub.api.ProjectApi;
import testhub.models.Project;
import testhub.models.ProjectQueryObject;

@Service
@Slf4j
public class DashboardListingService {

    @Autowired
    private DashboardRepository dashboardRepository;

    @Autowired
    private DashboardProjectRepository dashboardProjectRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ReportScheduleRepository reportScheduleRepository;

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private RedisDao redisDao;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectApi projectApi;

    @Autowired
    private HomeService homeService;

    private static final Long USER_CACHE_TTL_SECONDS = 3600L;
    private static final String USER_CACHE_KEY_PREFIX = "dashboard:users:group:";

    private static final Long PROJECT_CACHE_TTL_SECONDS = 3600L;
    private static final String PROJECT_CACHE_KEY_PREFIX = "dashboard:projects:group:";

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 100;

    public DashboardListResponse listDashboards(DashboardListRequest request) {
        log.debug("Listing dashboards with filters: projectIds={}, source={}, type={}, frequency={}",
                 request.getProjectIds(), request.getSource(), request.getType(), request.getFrequency());

        // Validate source parameter - only TCM or TO allowed
        if (Objects.nonNull(request.getSource()) && !request.getSource().trim().isEmpty()) {
            String normalizedSource = request.getSource().trim().toUpperCase();
            if (!normalizedSource.equals(Source.TCM.name()) && !normalizedSource.equals(Source.TO.name())) {
                log.warn("Invalid source parameter: {}. Only {} or {} allowed.",
                        request.getSource(), Source.TCM.name(), Source.TO.name());
                return DashboardListResponse.builder()
                        .error("Invalid source parameter. Only '" + Source.TCM.name() + "' or '" + Source.TO.name() + "' allowed.")
                        .data(Collections.emptyList())
                        .my_report_count(0)
                        .total_report_count(0)
                        .build();
            }
            request.setSource(normalizedSource);
        }

        // Capture before UDAC may modify projectIds
        final boolean userRequestedProjectFilter = Objects.nonNull(request.getProjectIds()) && !request.getProjectIds().isEmpty();

        request = applyUDACFiltering(request);

        final List<Long> requestProjectIds = request.getProjectIds();

        // Empty (non-null) means UDAC blocked all projects; return early before buildSpecification
        // silently drops the project predicate for empty lists.
        if (Objects.nonNull(requestProjectIds) && requestProjectIds.isEmpty()) {
            int ps = Objects.nonNull(request.getSize())
                    ? Math.min(MAX_PAGE_SIZE, Math.max(1, request.getSize()))
                    : DEFAULT_PAGE_SIZE;
            return DashboardListResponse.builder()
                    .data(Collections.emptyList())
                    .my_report_count(0)
                    .total_report_count(0)
                    .info(DashboardListResponse.PaginationInfo.builder()
                            .page(1).count(0).page_size(ps).next(null).prev(null)
                            .build())
                    .build();
        }

        Specification<Dashboard> spec = buildSpecification(request);
        Pageable pageable = buildPageable(request);
        Page<Dashboard> dashboardPage = dashboardRepository.findAll(spec, pageable);

        // Map TH project IDs -> TRA entities; reused by loadTraProjectInfoMap to avoid a second DB call.
        final List<Projects> thMappedProjects = (Objects.nonNull(requestProjectIds) && !requestProjectIds.isEmpty())
                ? projectRepository.findByThProjectIdIn(requestProjectIds)
                : Collections.emptyList();
        final List<Long> finalTraProjectIds = thMappedProjects.stream()
                .map(Projects::getId)
                .collect(Collectors.toList());
        final Map<Long, Projects> prefetchedProjectsById = thMappedProjects.stream()
                .collect(Collectors.toMap(Projects::getId, p -> p, (a, b) -> a));

        // TRA reports are post-filtered by project in Java (project IDs live in JSONB, not indexed)
        List<Dashboard> filteredDashboards = dashboardPage.getContent();
        if (Objects.nonNull(requestProjectIds) && !requestProjectIds.isEmpty()) {
            filteredDashboards = dashboardPage.getContent().stream()
                    .filter(dashboard -> {
                        if (isTmDashboard(dashboard)) {
                            return true; // TM already filtered by SQL
                        }
                        if (finalTraProjectIds.isEmpty()) {
                            // No TRA projects match requested TH project IDs
                            return !userRequestedProjectFilter;
                        }
                        Set<Long> reportProjectIds = extractTraProjectIds(dashboard);
                        if (reportProjectIds.isEmpty()) {
                            return !userRequestedProjectFilter;
                        }
                        return reportProjectIds.stream().anyMatch(finalTraProjectIds::contains);
                    })
                    .collect(Collectors.toList());
        }

        Map<UUID, ReportSchedule> scheduleMap = loadScheduleData(filteredDashboards);
        Map<UUID, List<Long>> dashboardProjectsMap = loadDashboardProjects(filteredDashboards);
        Map<Long, String> projectNameMap = loadProjectData(dashboardProjectsMap, filteredDashboards, request.getRequestingUserId());
        Map<UUID, List<ProjectInfo>> traProjectInfoMap = loadTraProjectInfoMap(filteredDashboards, prefetchedProjectsById);
        Map<String, UserCacheData> userMap = loadUserData(request.getGroupId());

        List<ReportSummary> summaries = filteredDashboards.stream()
                .map(dashboard -> convertToSummary(
                        dashboard,
                        scheduleMap.get(dashboard.getId()),
                        dashboardProjectsMap.getOrDefault(dashboard.getId(), Collections.emptyList()),
                        projectNameMap,
                        traProjectInfoMap.getOrDefault(dashboard.getId(), Collections.emptyList()),
                        userMap))
                .collect(Collectors.toList());

        // Pre-fetched once; shared by computeCorrectTotalCount and computeMyReportsCount.
        final List<Dashboard> allTraDashboards = userRequestedProjectFilter
                ? dashboardRepository.findAll(buildTraOnlySpec(request))
                : Collections.emptyList();

        // myCorrectTotal is userId-scoped (dashboardPage already filtered by userId).
        long myCorrectTotal = computeCorrectTotalCount(
                dashboardPage.getTotalElements(), userRequestedProjectFilter, finalTraProjectIds, allTraDashboards);

        // total_report_count must always reflect group-wide totals, ignoring userId.
        long totalReportCount;
        if (Objects.nonNull(request.getUserId())) {
            DashboardListRequest groupRequest = DashboardListRequest.builder()
                    .groupId(request.getGroupId())
                    .source(request.getSource())
                    .type(request.getType())
                    .frequency(request.getFrequency())
                    .projectIds(request.getProjectIds())
                    .query(request.getQuery())
                    .build();
            if (!userRequestedProjectFilter) {
                totalReportCount = dashboardRepository.count(buildSpecification(groupRequest));
            } else {
                List<Dashboard> allTraForGroup = dashboardRepository.findAll(buildTraOnlySpec(groupRequest));
                long groupSqlBase = dashboardRepository.count(buildSpecification(groupRequest));
                totalReportCount = computeCorrectTotalCount(groupSqlBase, userRequestedProjectFilter, finalTraProjectIds, allTraForGroup);
            }
        } else {
            totalReportCount = myCorrectTotal;
        }

        int myReportsCount;
        if (Objects.nonNull(request.getUserId())) {
            myReportsCount = (int) myCorrectTotal;
        } else {
            myReportsCount = (int) computeMyReportsCount(request, userRequestedProjectFilter, finalTraProjectIds, allTraDashboards);
        }

        DashboardListResponse.PaginationInfo.PaginationInfoBuilder infoBuilder = DashboardListResponse.PaginationInfo.builder()
                .page(dashboardPage.getNumber() + 1)
                .count((int) myCorrectTotal)
                .page_size(dashboardPage.getSize())
                .next(dashboardPage.hasNext() ? dashboardPage.getNumber() + 2 : null)
                .prev(dashboardPage.hasPrevious() ? dashboardPage.getNumber() : null);

        return DashboardListResponse.builder()
                .data(summaries)
                .my_report_count(myReportsCount)
                .total_report_count((int) totalReportCount)
                .info(infoBuilder.build())
                .build();
    }

    private DashboardListRequest applyUDACFiltering(DashboardListRequest request) {
        try {
            Integer subGroupId = homeService.getSubGroupId(request.getRequestingUserId());
            if (UserManager.getIsUDACEnabled()) {
                List<Long> allowedProjectIds = homeService.getProjectIdsList(subGroupId);
                if (Objects.nonNull(allowedProjectIds) && !allowedProjectIds.isEmpty()) {
                    List<Long> requestedProjectIds = request.getProjectIds();
                    if (Objects.nonNull(requestedProjectIds) && !requestedProjectIds.isEmpty()) {
                        request.setProjectIds(requestedProjectIds.stream()
                                .filter(allowedProjectIds::contains)
                                .collect(Collectors.toList()));
                    } else {
                        request.setProjectIds(allowedProjectIds);
                    }
                } else {
                    request.setProjectIds(Collections.emptyList());
                }
            }
        } catch (Exception e) {
            log.error("Error applying UDAC filtering for requestingUserId={}, groupId={}: {}",
                    request.getRequestingUserId(), request.getGroupId(), e.getMessage());
        }
        return request;
    }

    /** Corrects SQL total: TRA project filtering is Java-side, so SQL over-counts TRA rows. */
    private long computeCorrectTotalCount(
            long sqlBase,
            boolean userRequestedProjectFilter,
            List<Long> finalTraProjectIds,
            List<Dashboard> allTraDashboards) {

        if (!userRequestedProjectFilter) {
            return sqlBase;
        }

        long allTraInSql = allTraDashboards.size();

        if (finalTraProjectIds.isEmpty()) {
            return sqlBase - allTraInSql;
        }

        long matchingTraCount = allTraDashboards.stream()
                .filter(d -> {
                    Set<Long> reportProjectIds = extractTraProjectIds(d);
                    if (reportProjectIds.isEmpty()) return false;
                    return reportProjectIds.stream().anyMatch(finalTraProjectIds::contains);
                })
                .count();

        return sqlBase - allTraInSql + matchingTraCount;
    }

    private long computeMyReportsCount(
            DashboardListRequest request,
            boolean userRequestedProjectFilter,
            List<Long> finalTraProjectIds,
            List<Dashboard> allTraDashboards) {

        if (Objects.isNull(request.getRequestingUserId())) {
            return 0;
        }

        Long requestingUserId = request.getRequestingUserId();

        DashboardListRequest userRequest = DashboardListRequest.builder()
                .userId(requestingUserId)
                .groupId(request.getGroupId())
                .source(request.getSource())
                .type(request.getType())
                .frequency(request.getFrequency())
                .projectIds(request.getProjectIds())
                .query(request.getQuery())
                .build();

        long sqlTotal = dashboardRepository.count(buildSpecification(userRequest));

        if (!userRequestedProjectFilter) {
            return sqlTotal;
        }

        List<Dashboard> myTraDashboards = allTraDashboards.stream()
                .filter(d -> requestingUserId.equals(d.getUserId()))
                .collect(Collectors.toList());
        long allMyTraInSql = myTraDashboards.size();

        if (finalTraProjectIds.isEmpty()) {
            return sqlTotal - allMyTraInSql;
        }

        long matchingMyTraCount = myTraDashboards.stream()
                .filter(d -> {
                    Set<Long> rp = extractTraProjectIds(d);
                    if (rp.isEmpty()) return false;
                    return rp.stream().anyMatch(finalTraProjectIds::contains);
                })
                .count();

        return sqlTotal - allMyTraInSql + matchingMyTraCount;
    }

    /** TRA-only spec mirroring all non-project filters — used for total count correction. */
    private Specification<Dashboard> buildTraOnlySpec(DashboardListRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            List<String> traReportTypes = DashboardType.getTRAReportTypeNames();
            predicates.add(cb.and(
                root.get("type").in(traReportTypes),
                cb.isNull(root.get("externalReportId")),
                cb.notEqual(root.get("source"), Source.TCM.name())
            ));

            if (Objects.nonNull(request.getSource())) {
                predicates.add(cb.equal(root.get("source"), request.getSource()));
            }

            if (Objects.nonNull(request.getType()) && !request.getType().isEmpty()) {
                predicates.add(cb.equal(root.get("type"), request.getType()));
            }

            if (Objects.nonNull(request.getUserId())) {
                predicates.add(cb.equal(root.get("userId"), request.getUserId()));
            }

            if (Objects.nonNull(request.getGroupId())) {
                predicates.add(cb.equal(root.get("groupId"), request.getGroupId()));
            }

            applyFrequencyFilter(request, root, query, cb, predicates);

            if (Objects.nonNull(request.getQuery()) && !request.getQuery().trim().isEmpty()) {
                String likePattern = "%" + request.getQuery().trim().toLowerCase() + "%";
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), likePattern);
                Predicate typeMatch = cb.like(cb.lower(root.get("type")), likePattern);
                predicates.add(cb.or(nameMatch, typeMatch));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Specification<Dashboard> buildSpecification(DashboardListRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            List<String> traReportTypes = DashboardType.getTRAReportTypeNames();

            // Include: (1) TRA reports: type in TRA types AND no externalReportId AND source != TCM
            //          (2) TM reports: has externalReportId AND source = TCM
            Predicate traReports = cb.and(
                root.get("type").in(traReportTypes),
                cb.isNull(root.get("externalReportId")),
                cb.notEqual(root.get("source"), Source.TCM.name())
            );

            Predicate tmReports = cb.and(
                cb.isNotNull(root.get("externalReportId")),
                cb.equal(root.get("source"), Source.TCM.name())
            );

            predicates.add(cb.or(traReports, tmReports));

            // TM: filter via dashboard_projects table; TRA: post-filtered in Java (project IDs in JSONB)
            if (Objects.nonNull(request.getProjectIds()) && !request.getProjectIds().isEmpty()) {
                Subquery<UUID> tmProjectSubquery = query.subquery(UUID.class);
                Root<DashboardProject> dpRoot = tmProjectSubquery.from(DashboardProject.class);
                tmProjectSubquery.select(dpRoot.get("dashboardId"));
                tmProjectSubquery.where(
                    cb.equal(dpRoot.get("dashboardId"), root.get("id")),
                    dpRoot.get("thProjectId").in(request.getProjectIds())
                );

                Predicate tmWithProjects = cb.and(
                    cb.isNotNull(root.get("externalReportId")),
                    cb.equal(root.get("source"), Source.TCM.name()),
                    cb.exists(tmProjectSubquery)
                );
                Predicate traWithProjects = cb.and(
                    cb.isNull(root.get("externalReportId")),
                    cb.notEqual(root.get("source"), Source.TCM.name()),
                    root.get("type").in(traReportTypes)
                );
                predicates.add(cb.or(tmWithProjects, traWithProjects));
            }

            if (Objects.nonNull(request.getSource())) {
                predicates.add(cb.equal(root.get("source"), request.getSource().trim()));
            }

            if (Objects.nonNull(request.getType()) && !request.getType().isEmpty()) {
                predicates.add(cb.equal(root.get("type"), request.getType()));
            }

            if (Objects.nonNull(request.getUserId())) {
                predicates.add(cb.equal(root.get("userId"), request.getUserId()));
            }

            if (Objects.nonNull(request.getGroupId())) {
                predicates.add(cb.equal(root.get("groupId"), request.getGroupId()));
            }

            applyFrequencyFilter(request, root, query, cb, predicates);

            if (Objects.nonNull(request.getQuery()) && !request.getQuery().trim().isEmpty()) {
                String likePattern = "%" + request.getQuery().trim().toLowerCase() + "%";
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), likePattern);
                Predicate typeMatch = cb.like(cb.lower(root.get("type")), likePattern);
                predicates.add(cb.or(nameMatch, typeMatch));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void applyFrequencyFilter(
            DashboardListRequest request,
            Root<Dashboard> root,
            javax.persistence.criteria.CriteriaQuery<?> query,
            CriteriaBuilder cb,
            List<Predicate> predicates) {

        FrequencyFilter frequencyFilter = FrequencyFilter.fromValue(request.getFrequency());
        if (Objects.isNull(frequencyFilter)) {
            return;
        }

        Subquery<Long> scheduleSubquery = query.subquery(Long.class);
        Root<ReportSchedule> scheduleRoot = scheduleSubquery.from(ReportSchedule.class);
        scheduleSubquery.select(scheduleRoot.get("dashboardId"));

        Predicate dashboardIdMatch = cb.equal(scheduleRoot.get("dashboardId"), root.get("id"));

        if (frequencyFilter.isUnscheduled()) {
            scheduleSubquery.where(dashboardIdMatch);
            predicates.add(cb.not(cb.exists(scheduleSubquery)));

        } else if (frequencyFilter.isScheduled()) {
            scheduleSubquery.where(dashboardIdMatch);
            predicates.add(cb.exists(scheduleSubquery));

        } else if (frequencyFilter.isSpecificFrequency()) {
            Predicate frequencyMatch = cb.equal(
                    cb.lower(scheduleRoot.get("frequency")),
                    frequencyFilter.getValue()
            );
            scheduleSubquery.where(cb.and(dashboardIdMatch, frequencyMatch));
            predicates.add(cb.exists(scheduleSubquery));
        }
    }

    private Pageable buildPageable(DashboardListRequest request) {
        int page = Objects.nonNull(request.getPage()) ? Math.max(0, request.getPage() - 1) : 0;
        int requestedSize = Objects.nonNull(request.getSize()) ? request.getSize() : DEFAULT_PAGE_SIZE;
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, requestedSize));

        String sortBy = Objects.nonNull(request.getSortBy()) ? request.getSortBy() : "id";
        String sortOrder = Objects.nonNull(request.getSortOrder()) ? request.getSortOrder() : "desc";

        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder)
            ? Sort.Direction.ASC
            : Sort.Direction.DESC;

        return PageRequest.of(page, size, Sort.by(direction, sortBy));
    }

    private Map<UUID, ReportSchedule> loadScheduleData(List<Dashboard> dashboards) {
        List<UUID> dashboardIds = dashboards.stream()
                .map(Dashboard::getId)
                .collect(Collectors.toList());

        if (dashboardIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ReportSchedule> schedules = reportScheduleRepository.findByDashboardIdIn(dashboardIds);

        return schedules.stream()
                .collect(Collectors.toMap(
                    ReportSchedule::getDashboardId,
                    schedule -> schedule,
                    (s1, s2) -> s1
                ));
    }

    private Map<UUID, List<Long>> loadDashboardProjects(List<Dashboard> dashboards) {
        List<UUID> tmDashboardIds = dashboards.stream()
                .filter(d -> isTmDashboard(d))
                .map(Dashboard::getId)
                .collect(Collectors.toList());

        if (tmDashboardIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<DashboardProject> dps = dashboardProjectRepository.findByDashboardIdIn(tmDashboardIds);
        return dps.stream()
                .collect(Collectors.groupingBy(
                    DashboardProject::getDashboardId,
                    Collectors.mapping(DashboardProject::getThProjectId, Collectors.toList())
                ));
    }

    /** TRA project IDs from: data.widgets[].config.datasets.value[].filters.projects */
    private Set<Long> extractTraProjectIds(Dashboard dashboard) {
        if (Objects.isNull(dashboard.getData()) || Objects.isNull(dashboard.getData().getWidgets())) {
            return Collections.emptySet();
        }
        return dashboard.getData().getWidgets().stream()
                .filter(w -> Objects.nonNull(w.getConfig()) && Objects.nonNull(w.getConfig().getDatasets())
                        && Objects.nonNull(w.getConfig().getDatasets().getValue()))
                .flatMap(w -> w.getConfig().getDatasets().getValue().stream())
                .filter(sv -> Objects.nonNull(sv.getFilters()) && Objects.nonNull(sv.getFilters().getProjects()))
                .flatMap(sv -> sv.getFilters().getProjects().stream())
                .collect(Collectors.toSet());
    }

    private Map<UUID, List<ProjectInfo>> loadTraProjectInfoMap(
            List<Dashboard> dashboards,
            Map<Long, Projects> prefetchedProjectsById) {
        List<Dashboard> traDashboards = dashboards.stream()
                .filter(d -> !isTmDashboard(d))
                .collect(Collectors.toList());

        if (traDashboards.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> allTraProjectIds = traDashboards.stream()
                .flatMap(d -> extractTraProjectIds(d).stream())
                .collect(Collectors.toSet());

        if (allTraProjectIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<Long> idsToFetch = allTraProjectIds.stream()
                .filter(id -> !prefetchedProjectsById.containsKey(id))
                .collect(Collectors.toSet());

        Map<Long, Projects> projectById = new HashMap<>(prefetchedProjectsById);
        if (!idsToFetch.isEmpty()) {
            projectRepository.findAllById(idsToFetch)
                    .forEach(p -> projectById.put(p.getId(), p));
        }

        Map<UUID, List<ProjectInfo>> result = new HashMap<>();
        for (Dashboard dashboard : traDashboards) {
            Set<Long> projectIds = extractTraProjectIds(dashboard);
            if (!projectIds.isEmpty()) {
                List<ProjectInfo> projectInfos = projectIds.stream()
                        .map(projectById::get)
                        .filter(Objects::nonNull)
                        .map(p -> ProjectInfo.builder()
                                .project_id(p.getThProjectId())
                                .project_name(p.getName())
                                .build())
                        .filter(pi -> Objects.nonNull(pi.getProject_id()))
                        .collect(Collectors.toList());
                if (!projectInfos.isEmpty()) {
                    result.put(dashboard.getId(), projectInfos);
                }
            }
        }
        return result;
    }

    private Map<Long, String> loadProjectData(Map<UUID, List<Long>> dashboardProjectsMap, List<Dashboard> dashboards, Long userId) {
        Set<Long> thProjectIds = dashboardProjectsMap.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        if (thProjectIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Long groupId = dashboards.stream()
                .map(Dashboard::getGroupId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (Objects.isNull(groupId)) {
            return Collections.emptyMap();
        }

        String cacheKey = buildProjectCacheKey(groupId);
        Map<Long, String> cachedProjects = loadProjectCache(cacheKey);

        Set<Long> missingIds = thProjectIds.stream()
                .filter(id -> !cachedProjects.containsKey(id))
                .collect(Collectors.toSet());

        if (!missingIds.isEmpty()) {
            Map<Long, String> fetchedProjects = fetchProjectsFromTestHub(missingIds, groupId, userId);
            cachedProjects.putAll(fetchedProjects);
            saveProjectCache(cacheKey, cachedProjects);
        }

        return thProjectIds.stream()
                .filter(cachedProjects::containsKey)
                .collect(Collectors.toMap(id -> id, cachedProjects::get));
    }

    private Map<String, UserCacheData> loadUserData(Long groupId) {
        if (Objects.isNull(groupId)) {
            log.debug("GroupId is null, skipping user data cache");
            return fetchUserDataFromRails();
        }

        String cacheKey = buildUserCacheKey(groupId);

        try {
            String cachedData = redisDao.getValue(cacheKey);
            if (Objects.nonNull(cachedData) && !cachedData.isEmpty()) {
                return deserializeUserMap(cachedData);
            }
        } catch (Exception e) {
            log.debug("Redis cache read failed for group={}: {}", groupId, e.getMessage());
        }

        Map<String, UserCacheData> userMap = fetchUserDataFromRails();

        if (!userMap.isEmpty()) {
            try {
                String serialized = serializeUserMap(userMap);
                redisDao.setValue(cacheKey, serialized, USER_CACHE_TTL_SECONDS);
            } catch (Exception e) {
                log.error("Failed to cache user data for group={}: {}", groupId, e.getMessage());
            }
        }

        return userMap;
    }

    private Map<String, UserCacheData> fetchUserDataFromRails() {
        Map<String, UserCacheData> userMap = new HashMap<>();
        try {
            WidgetEnhancedFilterResp resp = dashboardService.getUserFilterValues(false);
            if (Objects.nonNull(resp) && Objects.nonNull(resp.getData()) && Objects.nonNull(resp.getData().get("others"))) {
                for (FilterValuesV2.FilterStruct user : resp.getData().get("others")) {
                    UserCacheData cacheData = UserCacheData.builder()
                            .fullName(user.getLabel())
                            .email(user.getMeta())
                            .build();
                    userMap.put(user.getValue(), cacheData);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fetch user details from Rails: {}", e.getMessage());
        }
        return userMap;
    }

    private Map<Long, String> fetchProjectsFromTestHub(Set<Long> projectIds, Long groupId, Long userId) {
        try {
            ProjectQueryObject query = new ProjectQueryObject();
            query.setId(new ArrayList<>(projectIds));

            String source = SourceUtil.getCommaSeparatedSourceFilterForTHSDK(
                    UserManager.getProductForCAD());

            testhub.models.Projects apiResponse = projectApi.getProjectsDashboard(
                    groupId,
                    userId,
                    getTransactionId(),
                    source,
                    query,
                    null,
                    1,
                    1000
            );
            if (Objects.isNull(apiResponse)) {
                return new HashMap<>();
            }
            List<Project> projects = apiResponse.getProjects();
            if (Objects.isNull(projects)) {
                return new HashMap<>();
            }

            return projects.stream()
                    .collect(Collectors.toMap(
                            Project::getId,
                            Project::getName,
                            (existing, replacement) -> existing
                    ));
        } catch (ApiException e) {
            log.error("Failed to fetch projects from TestHub: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private Map<Long, String> loadProjectCache(String cacheKey) {
        try {
            String cachedData = redisDao.getValue(cacheKey);
            if (Objects.nonNull(cachedData) && !cachedData.isEmpty()) {
                Map<Long, String> projectMap = objectMapper.readValue(
                        cachedData, new TypeReference<Map<Long, String>>() {});
                return new HashMap<>(projectMap);
            }
        } catch (Exception e) {
            log.debug("Failed to load project cache: {}", e.getMessage());
        }
        return new HashMap<>();
    }

    private void saveProjectCache(String cacheKey, Map<Long, String> projectMap) {
        try {
            String serialized = objectMapper.writeValueAsString(projectMap);
            redisDao.setValue(cacheKey, serialized, PROJECT_CACHE_TTL_SECONDS);
        } catch (Exception e) {
            log.error("Failed to save project cache: {}", e.getMessage());
        }
    }

    private String buildProjectCacheKey(Long groupId) {
        return PROJECT_CACHE_KEY_PREFIX + groupId;
    }

    private String getTransactionId() {
        return UUID.randomUUID().toString();
    }

    private String buildUserCacheKey(Long groupId) {
        return USER_CACHE_KEY_PREFIX + groupId;
    }

    private String serializeUserMap(Map<String, UserCacheData> userMap) throws Exception {
        return objectMapper.writeValueAsString(userMap);
    }

    private Map<String, UserCacheData> deserializeUserMap(String json) throws Exception {
        TypeReference<Map<String, UserCacheData>> typeRef =
            new TypeReference<Map<String, UserCacheData>>() {};
        return objectMapper.readValue(json, typeRef);
    }

    private boolean isTmDashboard(Dashboard dashboard) {
        return Objects.nonNull(dashboard.getExternalReportId())
                && Source.TCM.name().equals(dashboard.getSource());
    }

    private ReportSummary convertToSummary(
            Dashboard dashboard,
            ReportSchedule schedule,
            List<Long> thProjectIds,
            Map<Long, String> projectNameMap,
            List<ProjectInfo> traProjectInfos,
            Map<String, UserCacheData> userMap) {

        boolean isTmReport = isTmDashboard(dashboard);
        String sourceIndicator = dashboard.getSource();

        ReportSummary.ReportSummaryBuilder builder = ReportSummary.builder();

        if (isTmReport) {
            builder.id(dashboard.getExternalReportId());
        } else {
            builder.id(dashboard.getId());
        }

        builder.name(dashboard.getName())
               .level(dashboard.getLevel())
               .filters(null)
               .widgets(null)
               .totalWidget(0)
               .groupId(dashboard.getGroupId())
               .userId(dashboard.getUserId())
               .teamId(Objects.nonNull(dashboard.getTeamId()) ? dashboard.getTeamId() : -1L)
               .modifiedBy(dashboard.getModifiedBy())
               .createdAt(dashboard.getCreatedAt())
               .modifiedAt(dashboard.getModifiedAt())
               .source(sourceIndicator)
               .type(dashboard.getType())
               .isDefault(null)
               .isAutomation(null)
               .reports(null);

        if (isTmReport) {
            builder.external_report_id(dashboard.getExternalReportId())
                   .report_type(dashboard.getType())
                   .description(dashboard.getDescription())
                   .title(dashboard.getName())
                   .identifier(dashboard.getIdentifier());

            if (Objects.nonNull(dashboard.getFilters())) {
                builder.report_timeframe(dashboard.getFilters().getReportTimeframe())
                       .report_creation_mode(dashboard.getFilters().getReportCreationMode())
                       .custom_date_range(dashboard.getFilters().getCustomDateRange())
                       .is_dynamic(dashboard.getFilters().getIsDynamic());
            }
        }

        if (Objects.nonNull(schedule)) {
            builder.is_scheduled(true)
                   .frequency(schedule.getFrequency());

            var nextRunAt = schedule.getNextRunAt();
            if (Objects.nonNull(nextRunAt)) {
                builder.next_run_at(Date.from(nextRunAt));
            }

            builder.frequency_details(schedule.getFrequencyDetails());
        } else {
            builder.is_scheduled(false)
                   .frequency(null);
        }

        List<ProjectInfo> projects;
        if (isTmReport) {
            projects = (Objects.nonNull(thProjectIds) && !thProjectIds.isEmpty())
                ? thProjectIds.stream()
                    .filter(projectNameMap::containsKey) // skip deleted projects
                    .map(projectId -> ProjectInfo.builder()
                            .project_id(projectId)
                            .project_name(projectNameMap.get(projectId))
                            .build())
                    .collect(Collectors.toList())
                : Collections.emptyList();
        } else {
            projects = Objects.nonNull(traProjectInfos) ? traProjectInfos : Collections.emptyList();
        }

        if (!projects.isEmpty()) {
            builder.projects(projects);
            builder.project_id(projects.get(0).getProject_id())
                   .project_name(projects.get(0).getProject_name());
        }

        if (Objects.nonNull(dashboard.getUserId())) {
            UserCacheData user = userMap.get(String.valueOf(dashboard.getUserId()));
            builder.owner(OwnerInfo.builder()
                    .id(dashboard.getUserId())
                    .browserstack_user_id(dashboard.getUserId())
                    .full_name(Objects.nonNull(user) ? user.getFullName() : null)
                    .email(Objects.nonNull(user) ? user.getEmail() : null)
                    .build());
        }

        return builder.build();
    }
}

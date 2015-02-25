package org.everit.jira.timetracker.plugin;

/*
 * Copyright (c) 2011, Everit Kft.
 *
 * All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */

import java.io.Serializable;
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.everit.jira.timetracker.plugin.dto.ActionResult;
import org.everit.jira.timetracker.plugin.dto.ActionResultStatus;
import org.everit.jira.timetracker.plugin.dto.CalendarSettingsValues;
import org.everit.jira.timetracker.plugin.dto.EveritWorklog;
import org.everit.jira.timetracker.plugin.dto.EveritWorklogComparator;
import org.everit.jira.timetracker.plugin.dto.PluginSettingsValues;
import org.ofbiz.core.entity.EntityCondition;
import org.ofbiz.core.entity.EntityExpr;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericEntityException;
import org.ofbiz.core.entity.GenericValue;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import com.atlassian.jira.bc.JiraServiceContext;
import com.atlassian.jira.bc.JiraServiceContextImpl;
import com.atlassian.jira.bc.issue.worklog.WorklogInputParameters;
import com.atlassian.jira.bc.issue.worklog.WorklogInputParametersImpl;
import com.atlassian.jira.bc.issue.worklog.WorklogNewEstimateInputParameters;
import com.atlassian.jira.bc.issue.worklog.WorklogResult;
import com.atlassian.jira.bc.issue.worklog.WorklogService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.worklog.Worklog;
import com.atlassian.jira.issue.worklog.WorklogManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.mail.MailException;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

/**
 * The implementation of the {@link JiraTimetrackerPlugin}.
 */
public class JiraTimetrackerPluginImpl implements JiraTimetrackerPlugin,
Serializable, InitializingBean, DisposableBean {

    /**
     * Serial version UID.
     */
    private static final long serialVersionUID = 1L;
    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger
            .getLogger(JiraTimetrackerPluginImpl.class);

    /**
     * The plugin settings key prefix.
     */
    private static final String JTTP_PLUGIN_SETTINGS_KEY_PREFIX = "jttp";
    /**
     * The plugin setting Summary Filters key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_SUMMARY_FILTERS = "SummaryFilters";
    /**
     * The plugin setting Summary Filters key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_NON_ESTIMATED_ISSUES = "NonEstimated";
    /**
     * The plugin setting Exclude dates key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_EXCLUDE_DATES = "ExcludeDates";
    /**
     * The plugin setting Include dates key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_INCLUDE_DATES = "IncludeDates";
    /**
     * The plugin setting is calendar popup key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_IS_CALENDAR_POPUP = "isCalendarPopup";
    /**
     * The plugin setting is calendar popup key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_START_TIME_CHANGE = "startTimeChange";
    /**
     * The plugin setting is calendar popup key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_END_TIME_CHANGE = "endTimechange";
    /**
     * The plugin setting is actual date key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_IS_ACTUAL_DATE = "isActualDate";
    /**
     * The plugin setting is actual date key.
     */
    private static final String JTTP_PLUGIN_SETTINGS_IS_COLORIG = "isColoring";
    /**
     * A day in minutes.
     */
    private static final int ONE_DAY_IN_MINUTES = 1440;

    /**
     * The PluginSettingsFactory.
     */
    private final PluginSettingsFactory settingsFactory;
    /**
     * The plugin setting form the settingsFactory.
     */
    private PluginSettings pluginSettings;
    /**
     * The plugin global setting form the settingsFactory.
     */
    private PluginSettings globalSettings;
    /**
     * The plugin setting values.
     */
    private PluginSettingsValues pluginSettingsValues;
    /**
     * The issue check time in minutes.
     */
    private long issueCheckTimeInMinutes;
    /**
     * The exclude dates from the properties file.
     */
    private String excludeDatesString;
    /**
     * The include dates from the properties file.
     */
    private String includeDatesString;
    /**
     * The parsed exclude dates.
     */
    private Set<String> excludeDatesSet = new HashSet<String>();
    /**
     * The parsed include dates.
     */
    private Set<String> includeDatesSet = new HashSet<String>();
    /**
     * The summary filter issues ids.
     */
    private List<Pattern> summaryFilteredIssuePatterns;
    /**
     * The collector issues ids.
     */
    private List<Pattern> collectorIssuePatterns;
    /**
     * The summary filter issues ids.
     */
    private List<Pattern> defaultNonWorkingIssueIds = new ArrayList<Pattern>();
    /**
     * The collector issues ids.
     */
    private List<Pattern> defaultNonEstimedIssuePatterns = new ArrayList<Pattern>();
    /**
     * The plugin Scheduled Executor Service.
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors
            .newScheduledThreadPool(1);
    /**
     * The issues Estimated Time Checker Future.
     */
    private ScheduledFuture<?> issueEstimatedTimeCheckerFuture;
    /**
     * The JiraTimetrackerPluginImpl logger.
     */
    private Logger log = Logger.getLogger(JiraTimetrackerPluginImpl.class);

    /**
     * Default constructor.
     */
    public JiraTimetrackerPluginImpl(final PluginSettingsFactory settingFactory) {
        settingsFactory = settingFactory;
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        setDefaultVariablesValue();

        final Runnable issueEstimatedTimeChecker = new IssueEstimatedTimeChecker(
                this);

        // //TEST SETTINGS
        // Calendar now = Calendar.getInstance();
        // Long nowPlusTWOMin = (long) ((now.get(Calendar.HOUR_OF_DAY) * 60) +
        // now.get(Calendar.MINUTE) + 1);
        // issueEstimatedTimeCheckerFuture =
        // scheduledExecutorService.scheduleAtFixedRate(issueEstimatedTimeChecker,
        // calculateInitialDelay(nowPlusTWOMin), // FIXME fix the time
        // // calculateInitialDelay(issueCheckTimeInMinutes),
        // 5, TimeUnit.MINUTES);

        issueEstimatedTimeCheckerFuture = scheduledExecutorService
                .scheduleAtFixedRate(issueEstimatedTimeChecker,
                        calculateInitialDelay(issueCheckTimeInMinutes),
                        ONE_DAY_IN_MINUTES, TimeUnit.MINUTES);
    }

    private long calculateInitialDelay(final long time) {
        Calendar now = Calendar.getInstance();
        long hours = now.get(Calendar.HOUR_OF_DAY);
        long minutes = now.get(Calendar.MINUTE);
        long initialDelay = time - ((hours * 60) + minutes);
        if (initialDelay < 0) {
            initialDelay = initialDelay + ONE_DAY_IN_MINUTES;
        }
        return initialDelay;
    }

    private List<Long> createProjects(final ApplicationUser loggedInUser) {
        List<Project> projects = (List<Project>) ComponentAccessor.getPermissionManager()
                .getProjects(Permissions.BROWSE, loggedInUser);

        List<Long> projectList = new ArrayList<Long>();
        for (Project project : projects) {
            projectList.add(project.getId());
        }
        return projectList;
    }

    @Override
    public ActionResult createWorklog(final String issueId,
            final String comment, final String dateFormated,
            final String startTime, final String timeSpent) {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();
        log.warn("JTTP createWorklog: user: " + user.getDisplayName() + " "
                + user.getName() + " " + user.getEmailAddress());
        JiraServiceContext serviceContext = new JiraServiceContextImpl(user);
        log.warn("JTTP createWorklog: serviceContext User: "
                + user.getName() + " "
                + user.getEmailAddress());
        IssueManager issueManager = ComponentAccessor.getIssueManager();
        MutableIssue issue = issueManager.getIssueObject(issueId);
        if (issue == null) {
            return new ActionResult(ActionResultStatus.FAIL,
                    "plugin.invalid_issue", issueId);
        }
        PermissionManager permissionManager = ComponentAccessor.getPermissionManager();
        if (!permissionManager.hasPermission(Permissions.WORK_ISSUE, issue,
                user)) {
            return new ActionResult(ActionResultStatus.FAIL,
                    "plugin.nopermission_issue", issueId);
        }
        String dateAndTime = dateFormated + " " + startTime;
        Date date;
        try {
            date = DateTimeConverterUtil.stringToDateAndTime(dateAndTime);
        } catch (ParseException e) {
            return new ActionResult(ActionResultStatus.FAIL,
                    "plugin.date_parse", dateAndTime);
        }

        WorklogNewEstimateInputParameters params = WorklogInputParametersImpl
                .issue(issue).startDate(date).timeSpent(timeSpent)
                .comment(comment).buildNewEstimate();
        WorklogService worklogService = ComponentAccessor.getComponent(WorklogService.class);
        WorklogResult worklogResult = worklogService.validateCreate(
                serviceContext, params);
        if (worklogResult == null) {
            return new ActionResult(ActionResultStatus.FAIL,
                    "plugin.worklog.create.fail");
        }
        Worklog createdWorklog = worklogService.createAndAutoAdjustRemainingEstimate(serviceContext,
                worklogResult, true);
        if (createdWorklog == null) {
            return new ActionResult(ActionResultStatus.FAIL,
                    "plugin.worklog.create.fail");
        }

        return new ActionResult(ActionResultStatus.SUCCESS,
                "plugin.worklog.create.success");
    }

    private List<EntityCondition> createWorklogQueryExprList(final ApplicationUser user,
            final Calendar startDate, final Calendar endDate) {

        String userKey = user.getKey();

        EntityExpr startExpr = new EntityExpr("startdate",
                EntityOperator.GREATER_THAN_EQUAL_TO, new Timestamp(
                        startDate.getTimeInMillis()));
        EntityExpr endExpr = new EntityExpr("startdate",
                EntityOperator.LESS_THAN, new Timestamp(endDate.getTimeInMillis()));
        EntityExpr userExpr = new EntityExpr("author", EntityOperator.EQUALS,
                userKey);
        log.info("JTTP LOG: getWorklogs start date: " + startDate.toString()
                + " end date:" + endDate.toString());

        List<EntityCondition> exprList = new ArrayList<EntityCondition>();
        exprList.add(userExpr);
        if (startExpr != null) {
            exprList.add(startExpr);
        }
        if (endExpr != null) {
            exprList.add(endExpr);
        }
        return exprList;
    }

    private List<EntityCondition> createWorklogQueryExprListWithPermissionCheck(final String selectedUser,
            final ApplicationUser loggedInUser,
            final Calendar startDate, final Calendar endDate) throws GenericEntityException {
        String userKey = "";
        if ((selectedUser == null) || selectedUser.equals("")) {
            userKey = loggedInUser.getKey();
        } else {
            userKey = selectedUser;
        }

        List<Long> projects = createProjects(loggedInUser);

        EntityExpr startExpr = new EntityExpr("startdate",
                EntityOperator.GREATER_THAN_EQUAL_TO, new Timestamp(
                        startDate.getTimeInMillis()));
        EntityExpr endExpr = new EntityExpr("startdate",
                EntityOperator.LESS_THAN, new Timestamp(endDate.getTimeInMillis()));
        EntityExpr userExpr = new EntityExpr("author", EntityOperator.EQUALS,
                userKey);
        EntityExpr projectExpr = new EntityExpr("project", EntityOperator.IN, projects);
        log.info("JTTP LOG: getWorklogs start date: " + startDate.toString()
                + " end date:" + endDate.toString());

        List<EntityCondition> exprList = new ArrayList<EntityCondition>();
        exprList.add(userExpr);
        if (startExpr != null) {
            exprList.add(startExpr);
        }
        if (endExpr != null) {
            exprList.add(endExpr);
        }
        exprList.add(projectExpr);
        return exprList;
    }

    @Override
    public ActionResult deleteWorklog(final Long id) {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();
        JiraServiceContext serviceContext = new JiraServiceContextImpl(user);
        WorklogService worklogService = ComponentAccessor.getComponent(WorklogService.class);
        WorklogResult deleteWorklogResult = worklogService.validateDelete(
                serviceContext, id);
        if (deleteWorklogResult == null) {
            return new ActionResult(ActionResultStatus.FAIL,
                    "plugin.worklog.delete.fail", id.toString());
        }
        worklogService.deleteAndAutoAdjustRemainingEstimate(serviceContext,
                deleteWorklogResult, false);
        return new ActionResult(ActionResultStatus.SUCCESS,
                "plugin.worklog.delete.success", id.toString());
    }

    @Override
    public void destroy() throws Exception {
        scheduledExecutorService.shutdown();
        issueEstimatedTimeCheckerFuture.cancel(true);
        LOGGER.info("JiraTimetrackerPluginImpl destroyed");
    }

    @Override
    public ActionResult editWorklog(final Long id, final String issueId,
            final String comment, final String dateFormated, final String time,
            final String timeSpent) {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();
        JiraServiceContext serviceContext = new JiraServiceContextImpl(user);

        WorklogManager worklogManager = ComponentAccessor.getWorklogManager();
        Worklog worklog = worklogManager.getById(id);
        IssueManager issueManager = ComponentAccessor.getIssueManager();
        MutableIssue issue = issueManager.getIssueObject(issueId);
        if (issue == null) {
            return new ActionResult(ActionResultStatus.FAIL,
                    "plugin.invalide_issue", issueId);
        }
        if (!worklog.getIssue().getKey().equals(issueId)) {
            PermissionManager permissionManager = ComponentAccessor.getPermissionManager();
            if (!permissionManager.hasPermission(Permissions.WORK_ISSUE, issue,
                    user)) {
                return new ActionResult(ActionResultStatus.FAIL,
                        "plugin.nopermission_issue", issueId);
            }
            // ProjectPermissions.WORK_ON_ISSUES;
            ActionResult deleteResult = deleteWorklog(id);
            if (deleteResult.getStatus() == ActionResultStatus.FAIL) {
                return deleteResult;
            }
            // String dateCreate =
            // DateTimeConverterUtil.dateToString(worklog.getStartDate());
            // String dateCreate = date;
            ActionResult createResult = createWorklog(issueId, comment,
                    dateFormated, time, timeSpent);
            if (createResult.getStatus() == ActionResultStatus.FAIL) {
                return createResult;
            }
        } else {
            // String dateFormated =
            // DateTimeConverterUtil.dateToString(worklog.getStartDate());
            // String dateFormated = date;
            String dateAndTime = dateFormated + " " + time;
            Date dateCreate;
            try {
                dateCreate = DateTimeConverterUtil
                        .stringToDateAndTime(dateAndTime);
            } catch (ParseException e) {
                return new ActionResult(ActionResultStatus.FAIL,
                        "plugin.date_parse" + dateAndTime);
            }
            WorklogInputParameters params = WorklogInputParametersImpl
                    .issue(issue).startDate(dateCreate).timeSpent(timeSpent)
                    .comment(comment).worklogId(id).issue(issue).build();
            WorklogService worklogService = ComponentAccessor.getComponent(WorklogService.class);
            WorklogResult worklogResult = worklogService.validateUpdate(
                    serviceContext, params);
            if (worklogResult == null) {
                return new ActionResult(ActionResultStatus.FAIL,
                        "plugin.worklog.update.fail");
            }

            worklogService.updateAndAutoAdjustRemainingEstimate(serviceContext,
                    worklogResult, true);

        }
        return new ActionResult(ActionResultStatus.SUCCESS,
                "plugin.worklog.update.success");

    }

    @Override
    public Date firstMissingWorklogsDate(final String selectedUser) throws GenericEntityException {
        Calendar scannedDate = Calendar.getInstance();
        // one week
        scannedDate.set(Calendar.DAY_OF_YEAR,
                scannedDate.get(Calendar.DAY_OF_YEAR) - DateTimeConverterUtil.DAYS_PER_WEEK);
        for (int i = 0; i < DateTimeConverterUtil.DAYS_PER_WEEK; i++) {
            // convert date to String
            Date scanedDateDate = scannedDate.getTime();
            String scanedDateString = DateTimeConverterUtil
                    .dateToString(scanedDateDate);
            // check excludse - pass
            if (excludeDatesSet.contains(scanedDateString)) {
                scannedDate.set(Calendar.DAY_OF_YEAR,
                        scannedDate.get(Calendar.DAY_OF_YEAR) + 1);
                continue;
            }
            // check includes - not check weekend
            if (!includeDatesSet.contains(scanedDateString)) {
                // check weekend - pass
                if ((scannedDate.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                        || (scannedDate.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY)) {
                    scannedDate.set(Calendar.DAY_OF_YEAR,
                            scannedDate.get(Calendar.DAY_OF_YEAR) + 1);
                    continue;
                }
            }
            // check worklog. if no worklog set result else ++ scanedDate
            boolean isDateContainsWorklog = isContainsWorklog(selectedUser, scanedDateDate);
            if (!isDateContainsWorklog) {
                return scanedDateDate;
            } else {
                scannedDate.set(Calendar.DAY_OF_YEAR,
                        scannedDate.get(Calendar.DAY_OF_YEAR) + 1);
            }
        }
        // if we find everything all right then return with the current date
        return scannedDate.getTime();
    }

    @Override
    public List<Pattern> getCollectorIssuePatterns() {
        if (collectorIssuePatterns == null) {
            collectorIssuePatterns = defaultNonEstimedIssuePatterns;
        }
        return collectorIssuePatterns;
    }

    @Override
    public List<Date> getDates(final String selectedUser, final Date from, final Date to,
            final boolean workingHour, final boolean checkNonWorking)
                    throws GenericEntityException {
        List<Date> datesWhereNoWorklog = new ArrayList<Date>();
        Calendar fromDate = Calendar.getInstance();
        fromDate.setTime(from);
        Calendar toDate = Calendar.getInstance();
        toDate.setTime(to);
        while (!fromDate.after(toDate)) {
            String currentDateString = DateTimeConverterUtil.dateToString(fromDate.getTime());
            if (excludeDatesSet.contains(currentDateString)) {
                fromDate.add(Calendar.DATE, 1);
                continue;
            }
            // check includes - not check weekend
            if (!includeDatesSet.contains(currentDateString)) {
                // check weekend - pass
                if ((fromDate.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                        || (fromDate.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY)) {
                    fromDate.add(Calendar.DATE, 1);
                    continue;
                }
            }
            // check worklog. if no worklog set result else ++ scanedDate
            boolean isDateContainsWorklog;
            if (workingHour) {
                isDateContainsWorklog = isContainsEnoughWorklog(selectedUser, fromDate.getTime(),
                        checkNonWorking);
            } else {
                isDateContainsWorklog = isContainsWorklog(selectedUser, fromDate.getTime());
            }
            if (!isDateContainsWorklog) {
                datesWhereNoWorklog.add((Date) fromDate.getTime().clone());
            }
            fromDate.add(Calendar.DATE, 1);

        }
        Collections.reverse(datesWhereNoWorklog);
        return datesWhereNoWorklog;
    }

    @Override
    public List<String> getExluceDaysOfTheMonth(final String date) {
        List<String> resultexcludeDays = new ArrayList<String>();
        for (String exludeDate : excludeDatesSet) {
            // TODO this if not handle the 2013-4-04 date..... this is wrong or
            // not? .... think about it.
            if (exludeDate.startsWith(date.substring(0, 7))) {
                resultexcludeDays
                .add(exludeDate.substring(exludeDate.length() - 2));
            }
        }

        return resultexcludeDays;
    }

    @Override
    public List<Issue> getIssues() throws GenericEntityException {
        // List<GenericValue> issuesGV = null;
        // issuesGV = ComponentAccessor.getOfBizDelegator().findAll("Issue");
        List<Issue> issues = new ArrayList<Issue>();
        // for (GenericValue issueGV : issuesGV) {
        // issues.add(IssueImpl.getIssueObject(issueGV));
        // }
        return issues;
    }

    @Override
    public List<String> getLoggedDaysOfTheMonth(final String selectedUser, final Date date)
            throws GenericEntityException {
        List<String> resultDays = new ArrayList<String>();
        int dayOfMonth = 1;
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(date);
        startCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        Date start = startCalendar.getTime();

        while (dayOfMonth <= DateTimeConverterUtil.LAST_DAY_OF_MONTH) {
            if (isContainsWorklog(selectedUser, start)) {
                resultDays.add(Integer.toString(dayOfMonth));
            }
            startCalendar.set(Calendar.DAY_OF_MONTH, ++dayOfMonth);
            start = startCalendar.getTime();
        }

        return resultDays;
    }

    @Override
    public List<String> getProjectsId() throws GenericEntityException {
        List<String> projectsId = new ArrayList<String>();
        List<GenericValue> projectsGV = ComponentAccessor.getOfBizDelegator().findAll("Project");
        for (GenericValue project : projectsGV) {
            projectsId.add(project.getString("id"));
        }
        return projectsId;
    }

    @Override
    public EveritWorklog getWorklog(final Long worklogId) throws ParseException {
        WorklogManager worklogManager = ComponentAccessor.getWorklogManager();
        Worklog worklog = worklogManager.getById(worklogId);
        return new EveritWorklog(worklog);
    }

    @Override
    public List<EveritWorklog> getWorklogs(final String selectedUser, final Date date, final Date finalDate)
            throws ParseException, GenericEntityException {
        Calendar startDate = DateTimeConverterUtil.setDateToDayStart(date);
        Calendar endDate = (Calendar) startDate.clone();
        if (finalDate == null) {
            endDate.add(Calendar.DAY_OF_MONTH, 1);
        } else {
            endDate = DateTimeConverterUtil.setDateToDayStart(finalDate);
            endDate.add(Calendar.DAY_OF_MONTH, 1);
        }

        List<EveritWorklog> worklogs = new ArrayList<EveritWorklog>();

        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser loggedInUser = authenticationContext.getUser();

        String userKey = "";
        if ((selectedUser == null) || selectedUser.equals("")) {
            userKey = loggedInUser.getKey();
        } else {
            userKey = selectedUser;
        }

        List<EntityCondition> exprList = createWorklogQueryExprListWithPermissionCheck(userKey, loggedInUser,
                startDate, endDate);

        List<GenericValue> worklogGVList = ComponentAccessor.getOfBizDelegator()
                .findByAnd("IssueWorklogView", exprList);
        log.warn("JTTP LOG: getWorklogs worklog GV list size: " + worklogGVList.size());

        for (GenericValue worklogGv : worklogGVList) {
            EveritWorklog worklog = new EveritWorklog(worklogGv, collectorIssuePatterns);
            worklogs.add(worklog);
        }

        Collections.sort(worklogs, new EveritWorklogComparator());
        log.warn("JTTP LOG: getWorklogs worklog GV list size: "
                + worklogs.size());
        return worklogs;
    }

    /**
     * Check the given date is containt enough worklog. The worklog spent time have to be equlase or greater then 8
     * hours.
     *
     * @param date
     *            The date what have to check.
     * @param checkNonWorking
     *            Exclude or not the non-working issues.
     * @return True if the day contains enough worklog or weeked or exclude date.
     * @throws GenericEntityException
     *             If GenericEntity Exception.
     */
    private boolean isContainsEnoughWorklog(final String selectedUser, final Date date,
            final boolean checkNonWorking) throws GenericEntityException {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();

        Calendar startDate = DateTimeConverterUtil.setDateToDayStart(date);
        Calendar endDate = (Calendar) startDate.clone();
        endDate.add(Calendar.DAY_OF_MONTH, 1);

        List<EntityCondition> exprList = createWorklogQueryExprList(user, startDate, endDate);

        List<GenericValue> worklogGVList = ComponentAccessor.getOfBizDelegator().findByAnd("Worklog", exprList);
        if ((worklogGVList == null) || worklogGVList.isEmpty()) {
            return false;
        } else {
            if (checkNonWorking) {
                List<GenericValue> worklogsCopy = new ArrayList<GenericValue>();
                worklogsCopy.addAll(worklogGVList);
                // if we have non-estimated issues

                // TODO FIXME summaryFilteredIssuePatterns rename nonworking
                // pattern
                if ((summaryFilteredIssuePatterns != null)
                        && !summaryFilteredIssuePatterns.isEmpty()) {
                    for (GenericValue worklog : worklogsCopy) {
                        IssueManager issueManager = ComponentAccessor.getIssueManager();
                        Long issueId = worklog.getLong("issue");
                        MutableIssue issue = issueManager
                                .getIssueObject(issueId);
                        for (Pattern issuePattern : summaryFilteredIssuePatterns) {
                            boolean issueMatches = issuePattern.matcher(
                                    issue.getKey()).matches();
                            // if match not count in summary
                            if (issueMatches) {
                                worklogGVList.remove(worklog);
                                break;
                            }
                        }
                    }
                }
            }
            long timeSpent = 0;
            for (GenericValue worklog : worklogGVList) {
                timeSpent += worklog.getLong("timeworked").longValue();
            }
            if (timeSpent < DateTimeConverterUtil.EIGHT_HOUR_IN_SECONDS) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check the given date, the user have worklogs or not.
     *
     * @param date
     *            The date what have to check.
     * @return If The user have worklogs the given date then true, esle false.
     * @throws GenericEntityException
     *             GenericEntity Exception.
     */
    private boolean isContainsWorklog(final String selectedUser, final Date date)
            throws GenericEntityException {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();

        Calendar startDate = DateTimeConverterUtil.setDateToDayStart(date);
        Calendar endDate = (Calendar) startDate.clone();
        endDate.add(Calendar.DAY_OF_MONTH, 1);

        List<EntityCondition> exprList = createWorklogQueryExprList(user, startDate,
                endDate);

        List<GenericValue> worklogGVList = ComponentAccessor.getOfBizDelegator().findByAnd("Worklog", exprList);
        if ((worklogGVList == null) || worklogGVList.isEmpty()) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public String lastEndTime(final List<EveritWorklog> worklogs)
            throws ParseException {
        if ((worklogs == null) || (worklogs.size() == 0)) {
            return "08:00";
        }
        String endTime = worklogs.get(0).getEndTime();
        for (int i = 1; i < worklogs.size(); i++) {
            Date first = DateTimeConverterUtil.stringTimeToDateTime(worklogs
                    .get(i - 1).getEndTime());
            Date second = DateTimeConverterUtil.stringTimeToDateTime(worklogs
                    .get(i).getEndTime());
            if (first.compareTo(second) == 1) {
                endTime = worklogs.get(i - 1).getEndTime();
            } else {
                endTime = worklogs.get(i).getEndTime();
            }
        }
        return endTime;
    }

    @Override
    public PluginSettingsValues loadPluginSettings() {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();

        globalSettings = settingsFactory.createGlobalSettings();
        List<String> tempIssuePatternList = (List<String>) globalSettings
                .get(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                        + JTTP_PLUGIN_SETTINGS_SUMMARY_FILTERS);
        if (tempIssuePatternList != null) {
            // add non working issues
            summaryFilteredIssuePatterns = new ArrayList<Pattern>();
            for (String tempIssuePattern : tempIssuePatternList) {
                summaryFilteredIssuePatterns.add(Pattern
                        .compile(tempIssuePattern));
            }
        } else {
            // default! from properties load default issues!!
            summaryFilteredIssuePatterns = defaultNonWorkingIssueIds;

        }
        tempIssuePatternList = (List<String>) globalSettings
                .get(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                        + JTTP_PLUGIN_SETTINGS_NON_ESTIMATED_ISSUES);
        if (tempIssuePatternList != null) {
            // add collector issues
            collectorIssuePatterns = new ArrayList<Pattern>();
            for (String tempIssuePattern : tempIssuePatternList) {
                collectorIssuePatterns.add(Pattern.compile(tempIssuePattern));
            }
        } else {
            collectorIssuePatterns = defaultNonEstimedIssuePatterns;
        }
        String tempSpecialDates = (String) globalSettings
                .get(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                        + JTTP_PLUGIN_SETTINGS_EXCLUDE_DATES);
        if (tempSpecialDates != null) {
            excludeDatesString = tempSpecialDates;
            excludeDatesSet = new HashSet<String>();
            for (String excludeDate : excludeDatesString.split(",")) {
                excludeDatesSet.add(excludeDate);
            }
        } else {
            // Default Empty
            excludeDatesSet = new HashSet<String>();
            excludeDatesString = "";
        }
        tempSpecialDates = (String) globalSettings
                .get(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                        + JTTP_PLUGIN_SETTINGS_INCLUDE_DATES);
        if (tempSpecialDates != null) {
            includeDatesString = tempSpecialDates;
            includeDatesSet = new HashSet<String>();
            for (String includeDate : includeDatesString.split(",")) {
                includeDatesSet.add(includeDate);
            }
        } else {
            // Default Empty
            includeDatesSet = new HashSet<String>();
            includeDatesString = "";
        }

        pluginSettings = settingsFactory
                .createSettingsForKey(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                        + user.getName());
        Integer isPopup = null;
        if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_IS_CALENDAR_POPUP) != null) {
            try {
                isPopup = Integer.valueOf(pluginSettings.get(
                        JTTP_PLUGIN_SETTINGS_IS_CALENDAR_POPUP).toString());
            } catch (NumberFormatException e) {
                // the default is the popup calendar
                LOGGER.error(
                        "Wrong formated calender type. Set the default value (popup).",
                        e);
                isPopup = JiraTimetrackerUtil.POPUP_CALENDAR_CODE;
            }
        } else {
            // the default is the popup calendar
            isPopup = JiraTimetrackerUtil.POPUP_CALENDAR_CODE;
        }
        Boolean isActualDate = null;
        if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_IS_ACTUAL_DATE) != null) {
            if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_IS_ACTUAL_DATE).equals(
                    "true")) {
                isActualDate = true;
            } else if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_IS_ACTUAL_DATE)
                    .equals("false")) {
                isActualDate = false;
            }
        } else {
            // the default is the Actual Date
            isActualDate = true;
        }
        Boolean isColoring = null;
        if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_IS_COLORIG) != null) {
            if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_IS_COLORIG).equals(
                    "true")) {
                isColoring = true;
            } else if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_IS_COLORIG)
                    .equals("false")) {
                isColoring = false;
            }
        } else {
            // the default coloring is TRUE
            isColoring = true;
        }

        // SET startTime Change the default value is 5
        int startTimeChange = 5;

        if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_START_TIME_CHANGE) != null) {
            try {
                startTimeChange = Integer.valueOf(pluginSettings.get(
                        JTTP_PLUGIN_SETTINGS_START_TIME_CHANGE).toString());
                if (!validateTimeChange(Integer.toString(startTimeChange))) {
                    startTimeChange = 5;
                }
            } catch (NumberFormatException e) {
                LOGGER.error(
                        "Wrong formated startTime change value. Set the default value (1).",
                        e);
            }
        }
        // SET endtTime Change the defaulte value is 5
        int endTimeChange = 5;

        if (pluginSettings.get(JTTP_PLUGIN_SETTINGS_END_TIME_CHANGE) != null) {
            try {
                endTimeChange = Integer.valueOf(pluginSettings.get(
                        JTTP_PLUGIN_SETTINGS_END_TIME_CHANGE).toString());
                if (!validateTimeChange(Integer.toString(endTimeChange))) {
                    endTimeChange = 5;
                }
            } catch (NumberFormatException e) {
                LOGGER.error(
                        "Wrong formated startTime change value. Set the default value (1).",
                        e);
            }
        }
        // Here set the other values
        pluginSettingsValues = new PluginSettingsValues(
                new CalendarSettingsValues(isPopup, isActualDate,
                        excludeDatesString, includeDatesString, isColoring),
                        summaryFilteredIssuePatterns, collectorIssuePatterns,
                        startTimeChange, endTimeChange);
        return pluginSettingsValues;
    }

    @Override
    public void savePluginSettings(
            final PluginSettingsValues pluginSettingsParameters) {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();
        pluginSettings = settingsFactory
                .createSettingsForKey(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                        + user.getName());
        pluginSettings.put(JTTP_PLUGIN_SETTINGS_IS_CALENDAR_POPUP,
                Integer.toString(pluginSettingsParameters.isCalendarPopup()));
        pluginSettings.put(JTTP_PLUGIN_SETTINGS_IS_ACTUAL_DATE,
                pluginSettingsParameters.isActualDate().toString());
        pluginSettings.put(JTTP_PLUGIN_SETTINGS_IS_COLORIG,
                pluginSettingsParameters.isColoring().toString());
        pluginSettings
        .put(JTTP_PLUGIN_SETTINGS_START_TIME_CHANGE,
                Integer.toString(pluginSettingsParameters
                        .getStartTimeChange()));
        pluginSettings.put(JTTP_PLUGIN_SETTINGS_END_TIME_CHANGE,
                Integer.toString(pluginSettingsParameters.getEndTimeChange()));

        globalSettings = settingsFactory.createGlobalSettings();
        globalSettings.put(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                + JTTP_PLUGIN_SETTINGS_SUMMARY_FILTERS,
                pluginSettingsParameters.getFilteredSummaryIssues());
        globalSettings.put(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                + JTTP_PLUGIN_SETTINGS_NON_ESTIMATED_ISSUES,
                pluginSettingsParameters.getCollectorIssues());
        globalSettings.put(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                + JTTP_PLUGIN_SETTINGS_EXCLUDE_DATES,
                pluginSettingsParameters.getExcludeDates());
        globalSettings.put(JTTP_PLUGIN_SETTINGS_KEY_PREFIX
                + JTTP_PLUGIN_SETTINGS_INCLUDE_DATES,
                pluginSettingsParameters.getIncludeDates());
    }

    /**
     * Set the default values of the important variables.
     *
     * @throws MailException
     */
    private void setDefaultVariablesValue() throws MailException {
        // DEFAULT 20:00
        issueCheckTimeInMinutes = 1200;
        // Default exclude and include dates set are empty. No DATA!!
        // Default: no non working issue. we simple use the empty list
        // defaultNonWorkingIssueIds = new ArrayList<Long>();
        // The default non estimted issues regex. All issue non estimeted.
        defaultNonEstimedIssuePatterns = new ArrayList<Pattern>();
        defaultNonEstimedIssuePatterns.add(Pattern.compile(".*"));
    }

    @Override
    public String summary(final String selectedUser, final Date startSummary, final Date finishSummary,
            final List<Pattern> issuePatterns) throws GenericEntityException {
        JiraAuthenticationContext authenticationContext = ComponentAccessor.getJiraAuthenticationContext();
        ApplicationUser user = authenticationContext.getUser();

        Calendar start = Calendar.getInstance();
        start.setTime(startSummary);
        Calendar finish = Calendar.getInstance();
        finish.setTime(finishSummary);

        // List<EntityExpr> exprList = createWorklogQueryExprList(selectedUser, user,
        // startSummary, finishSummary);
        List<EntityCondition> exprList = createWorklogQueryExprList(user,
                start, finish);

        List<GenericValue> worklogs;
        // worklog query
        worklogs = ComponentAccessor.getOfBizDelegator().findByAnd("Worklog",
                exprList);
        List<GenericValue> worklogsCopy = new ArrayList<GenericValue>();
        worklogsCopy.addAll(worklogs);
        // if we have non-estimated issues
        if ((issuePatterns != null) && !issuePatterns.isEmpty()) {
            for (GenericValue worklog : worklogsCopy) {
                IssueManager issueManager = ComponentAccessor.getIssueManager();
                Long issueId = worklog.getLong("issue");
                MutableIssue issue = issueManager.getIssueObject(issueId);
                for (Pattern issuePattern : issuePatterns) {
                    boolean issueMatches = issuePattern.matcher(issue.getKey())
                            .matches();
                    // if match not count in summary
                    if (issueMatches) {
                        worklogs.remove(worklog);
                        break;
                    }
                }
            }
        }
        long timeSpent = 0;
        // Iterator<GenericValue> worklogsIterator = worklogs.iterator();
        // while (worklogsIterator.hasNext()) {
        // GenericValue worklog = worklogsIterator.next();
        // timeSpent = timeSpent + worklog.getLong("timeworked").longValue();
        // }
        for (GenericValue worklog : worklogs) {
            timeSpent += worklog.getLong("timeworked").longValue();
        }
        return DateTimeConverterUtil.secondConvertToString(timeSpent);
    }

    @Override
    public boolean validateTimeChange(final String changeValue)
            throws NumberFormatException {
        int changeValueInt = Integer.valueOf(changeValue);

        switch (changeValueInt) {
        case 5:
            return true;
        case 10:
            return true;
        case 15:
            return true;
        case 20:
            return true;
        case 30:
            return true;
        default:
            return false;
        }

    }

}

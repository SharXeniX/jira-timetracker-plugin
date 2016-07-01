/*
 * Copyright (C) 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.everit.jira.reporting.plugin.web;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;
import org.everit.jira.analytics.AnalyticsDTO;
import org.everit.jira.reporting.plugin.ReportingCondition;
import org.everit.jira.reporting.plugin.ReportingPlugin;
import org.everit.jira.reporting.plugin.util.PermissionUtil;
import org.everit.jira.timetracker.plugin.DurationFormatter;
import org.everit.jira.timetracker.plugin.JiraTimetrackerAnalytics;
import org.everit.jira.timetracker.plugin.JiraTimetrackerPlugin;
import org.everit.jira.timetracker.plugin.PluginCondition;
import org.everit.jira.timetracker.plugin.dto.EveritWorklog;
import org.everit.jira.timetracker.plugin.dto.PluginSettingsValues;
import org.everit.jira.timetracker.plugin.dto.TimetrackerReportsSessionData;
import org.everit.jira.timetracker.plugin.util.JiraTimetrackerUtil;
import org.everit.jira.timetracker.plugin.util.PiwikPropertiesUtil;
import org.everit.jira.timetracker.plugin.util.PropertiesUtil;
import org.ofbiz.core.entity.GenericEntityException;

import com.atlassian.jira.avatar.Avatar;
import com.atlassian.jira.avatar.AvatarService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.exception.DataAccessException;
import com.atlassian.jira.issue.RendererManager;
import com.atlassian.jira.issue.fields.renderer.IssueRenderContext;
import com.atlassian.jira.issue.fields.renderer.JiraRendererPlugin;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

/**
 * The Timetracker table report action support class.
 */
public class JiraTimetrackerTableWebAction extends JiraWebActionSupport {

  /**
   * EveritWorklog comparator by Date.
   */
  private static class OrderByDate implements Comparator<EveritWorklog>, Serializable {
    private static final long serialVersionUID = 2000628478189889582L;

    @Override
    public int compare(final EveritWorklog wl1, final EveritWorklog wl2) {
      return wl1.getDate().compareTo(wl2.getDate());
    }
  }

  private static final String EXCEEDED_A_YEAR = "plugin.exceeded.year";

  private static final String FREQUENT_FEEDBACK = "jttp.plugin.frequent.feedback";

  private static final String GET_WORKLOGS_ERROR_MESSAGE = "Error when trying to get worklogs.";

  private static final String INVALID_END_TIME = "plugin.invalid_endTime";

  private static final String INVALID_START_TIME = "plugin.invalid_startTime";

  private static final String INVALID_USER_PICKER = "plugin.user.picker.label";

  /**
   * The Issue Collector jttp_build.porperties key.
   */
  private static final String ISSUE_COLLECTOR_SRC = "ISSUE_COLLECTOR_SRC";

  private static final String JIRA_HOME_URL = "/secure/Dashboard.jspa";

  /**
   * Logger.
   */
  private static final Logger LOGGER = Logger.getLogger(JiraTimetrackerTableWebAction.class);

  private static final int MILLISEC_IN_SEC = 1000;

  private static final String NOT_RATED = "Not rated";

  private static final String PARAM_DATEFROM = "dateFromMil";

  private static final String PARAM_DATETO = "dateToMil";

  private static final String PARAM_USERPICKER = "selectedUser";

  private static final String SELF_WITH_DATE_AND_USER_URL_FORMAT =
      "/secure/JiraTimetrackerTableWebAction.jspa"
          + "?dateFromMil=%s"
          + "&dateToMil=%s"
          + "&selectedUser=%s"
          + "&search";

  /**
   * Serial version UID.
   */
  private static final long serialVersionUID = 1L;

  private static final String SESSION_KEY = "jttpTableStore";

  private static final String WRONG_DATES = "plugin.wrong.dates";

  private AnalyticsDTO analyticsDTO;

  private JiraRendererPlugin atlassianWikiRenderer;

  private String avatarURL = "";

  private String contextPath;

  private String currentUser = "";

  /**
   * The formated date.
   */
  private Long dateFromFormated;

  /**
   * The formated date.
   */
  private Long dateToFormated;

  private HashMap<Integer, List<Object>> daySum = new HashMap<Integer, List<Object>>();

  private DurationFormatter durationFormatter;

  public boolean hasBrowseUsersPermission = true;

  private String issueCollectorSrc;

  private IssueRenderContext issueRenderContext;

  private List<Pattern> issuesRegex;

  /**
   * The {@link JiraTimetrackerPlugin}.
   */
  private final JiraTimetrackerPlugin jiraTimetrackerPlugin;

  /**
   * The message.
   */
  private String message = "";

  private HashMap<Integer, List<Object>> monthSum = new HashMap<Integer, List<Object>>();

  private PluginCondition pluginCondition;

  private final PluginSettingsFactory pluginSettingsFactory;

  private final HashMap<Integer, List<Object>> realDaySum = new HashMap<Integer, List<Object>>();

  private final HashMap<Integer, List<Object>> realMonthSum = new HashMap<Integer, List<Object>>();

  private final HashMap<Integer, List<Object>> realWeekSum = new HashMap<Integer, List<Object>>();

  private ReportingCondition reportingCondition;

  private ReportingPlugin reportingPlugin;

  private transient ApplicationUser userPickerObject;

  private HashMap<Integer, List<Object>> weekSum = new HashMap<Integer, List<Object>>();

  private List<EveritWorklog> worklogs;

  /**
   * Simple constructor.
   *
   * @param jiraTimetrackerPlugin
   *          The {@link JiraTimetrackerPlugin}.
   * @param pluginSettingsFactory
   *          the {@link PluginSettingsFactory}.
   */
  public JiraTimetrackerTableWebAction(
      final JiraTimetrackerPlugin jiraTimetrackerPlugin,
      final ReportingPlugin reportingPlugin,
      final PluginSettingsFactory pluginSettingsFactory) {
    this.jiraTimetrackerPlugin = jiraTimetrackerPlugin;
    this.reportingPlugin = reportingPlugin;
    reportingCondition = new ReportingCondition(this.reportingPlugin);
    this.pluginSettingsFactory = pluginSettingsFactory;
    pluginCondition = new PluginCondition(jiraTimetrackerPlugin);
    issueRenderContext = new IssueRenderContext(null);
    RendererManager rendererManager = ComponentAccessor.getRendererManager();
    atlassianWikiRenderer = rendererManager.getRendererForType("atlassian-wiki-renderer");
  }

  private void addToDaySummary(final EveritWorklog worklog) {
    int dayNo = worklog.getDayNo();
    ArrayList<Object> list = new ArrayList<Object>();
    Long prevDaySum = (daySum.get(dayNo) == null) ? Long.valueOf(0)
        : (Long) daySum.get(dayNo).get(0);
    Long sumSec = prevDaySum + (worklog.getMilliseconds() / MILLISEC_IN_SEC);
    list.add(sumSec);
    list.add(durationFormatter.exactDuration(sumSec));
    daySum.put(dayNo, list);
  }

  private void addToMonthSummary(final EveritWorklog worklog) {
    int monthNo = worklog.getMonthNo();
    ArrayList<Object> list = new ArrayList<Object>();
    Long prevMonthSum = (monthSum.get(monthNo) == null) ? Long.valueOf(0)
        : (Long) monthSum.get(monthNo).get(0);
    Long sumSec = prevMonthSum + (worklog.getMilliseconds() / MILLISEC_IN_SEC);
    list.add(sumSec);
    list.add(durationFormatter.exactDuration(sumSec));
    monthSum.put(monthNo, list);
  }

  private void addToRealDaySummary(final EveritWorklog worklog, final boolean isRealWorklog) {
    int dayNo = worklog.getDayNo();
    ArrayList<Object> realList = new ArrayList<Object>();
    Long prevRealDaySum = (realDaySum.get(dayNo) == null) ? Long.valueOf(0)
        : (Long) realDaySum.get(dayNo).get(0);
    Long realSumSec = prevRealDaySum;
    if (isRealWorklog) {
      realSumSec += (worklog.getMilliseconds() / MILLISEC_IN_SEC);
    }
    realList.add(realSumSec);
    realList.add(durationFormatter.exactDuration(realSumSec));
    realDaySum.put(dayNo, realList);
  }

  private void addToRealMonthSummary(final EveritWorklog worklog, final boolean isRealWorklog) {
    int monthNo = worklog.getMonthNo();
    ArrayList<Object> realList = new ArrayList<Object>();
    Long prevRealMonthSum = realMonthSum.get(monthNo) == null ? Long.valueOf(0)
        : (Long) realMonthSum.get(monthNo).get(0);
    Long realSumSec = prevRealMonthSum;
    if (isRealWorklog) {
      realSumSec += (worklog.getMilliseconds() / MILLISEC_IN_SEC);
    }
    realList.add(realSumSec);
    realList.add(durationFormatter.exactDuration(realSumSec));
    realMonthSum.put(monthNo, realList);
  }

  private void addToRealWeekSummary(final EveritWorklog worklog, final boolean isRealWorklog) {
    int weekNo = worklog.getWeekNo();
    ArrayList<Object> realList = new ArrayList<Object>();
    Long prevRealWeekSum = realWeekSum.get(weekNo) == null ? Long.valueOf(0)
        : (Long) realWeekSum.get(weekNo).get(0);
    Long realSumSec = prevRealWeekSum;
    if (isRealWorklog) {
      realSumSec += (worklog.getMilliseconds() / MILLISEC_IN_SEC);
    }
    realList.add(realSumSec);
    realList.add(durationFormatter.exactDuration(realSumSec));
    realWeekSum.put(weekNo, realList);
  }

  private void addToWeekSummary(final EveritWorklog worklog) {
    ArrayList<Object> list = new ArrayList<Object>();
    int weekNo = worklog.getWeekNo();
    Long prevWeekSum = weekSum.get(weekNo) == null ? Long.valueOf(0)
        : (Long) weekSum.get(weekNo).get(0);
    Long sumSec = prevWeekSum + (worklog.getMilliseconds() / MILLISEC_IN_SEC);
    list.add(sumSec);
    list.add(durationFormatter.exactDuration(sumSec));
    weekSum.put(weekNo, list);
  }

  private String checkConditions() {
    boolean isUserLogged = JiraTimetrackerUtil.isUserLogged();
    if (!isUserLogged) {
      setReturnUrl(JIRA_HOME_URL);
      return getRedirect(NONE);
    }
    if (!reportingCondition.shouldDisplay(getLoggedInApplicationUser(), null)) {
      setReturnUrl(JIRA_HOME_URL);
      return getRedirect(NONE);
    }
    if (!pluginCondition.shouldDisplay(getLoggedInApplicationUser(), null)) {
      setReturnUrl(JIRA_HOME_URL);
      return getRedirect(NONE);
    }
    return null;
  }

  private void createDurationFormatter() {
    durationFormatter = new DurationFormatter();
  }

  @Override
  public String doDefault() throws ParseException {
    String checkConditionsResult = checkConditions();
    if (checkConditionsResult != null) {
      return checkConditionsResult;
    }

    hasBrowseUsersPermission =
        PermissionUtil.hasBrowseUserPermission(getLoggedInApplicationUser(), reportingPlugin);

    createDurationFormatter();

    loadIssueCollectorSrc();
    normalizeContextPath();

    loadPluginSettingAndParseResult();
    analyticsDTO = JiraTimetrackerAnalytics.getAnalyticsDTO(pluginSettingsFactory,
        PiwikPropertiesUtil.PIWIK_TABLE_SITEID);

    boolean loadedFromSession = loadDataFromSession();
    initDatesIfNecessary();
    initCurrentUserIfNecessary();

    if (loadedFromSession) {
      setReturnUrl(getFormattedRedirectUrl());
      return getRedirect(NONE);
    } else {
      return INPUT;
    }
  }

  @Override
  public String doExecute() throws ParseException, GenericEntityException {
    String checkConditionsResult = checkConditions();
    if (checkConditionsResult != null) {
      return checkConditionsResult;
    }

    createDurationFormatter();

    loadIssueCollectorSrc();
    normalizeContextPath();
    loadPluginSettingAndParseResult();
    hasBrowseUsersPermission =
        PermissionUtil.hasBrowseUserPermission(getLoggedInApplicationUser(), reportingPlugin);

    analyticsDTO = JiraTimetrackerAnalytics.getAnalyticsDTO(pluginSettingsFactory,
        PiwikPropertiesUtil.PIWIK_TABLE_SITEID);

    if (parseFeedback()) {
      loadDataFromSession();
      initDatesIfNecessary();
      initCurrentUserIfNecessary();
      return INPUT;
    }

    Calendar startDate = null;
    Calendar lastDate = null;
    try {
      setCurrentUserFromParam();
      setUserPickerObjectBasedOnCurrentUser();
      startDate = parseDateFrom();
      lastDate = parseDateTo();
      validateDates(startDate, lastDate);
    } catch (IllegalArgumentException e) {
      message = e.getMessage();
      return INPUT;
    }

    worklogs = new ArrayList<EveritWorklog>();
    try {
      worklogs.addAll(jiraTimetrackerPlugin.getWorklogs(currentUser, startDate.getTime(),
          lastDate.getTime()));
      saveDataToSession();
    } catch (DataAccessException | SQLException e) {
      LOGGER.error(GET_WORKLOGS_ERROR_MESSAGE, e);
      return ERROR;
    }

    Collections.sort(worklogs, new OrderByDate());

    for (EveritWorklog worklog : worklogs) {
      Calendar calendar = Calendar.getInstance();
      calendar.setTime(worklog.getDate());

      boolean isRealWorklog = isRealWorklog(worklog);

      addToMonthSummary(worklog);
      addToRealMonthSummary(worklog, isRealWorklog);

      addToWeekSummary(worklog);
      addToRealWeekSummary(worklog, isRealWorklog);

      addToDaySummary(worklog);
      addToRealDaySummary(worklog, isRealWorklog);
    }

    return SUCCESS;
  }

  public AnalyticsDTO getAnalyticsDTO() {
    return analyticsDTO;
  }

  public JiraRendererPlugin getAtlassianWikiRenderer() {
    return atlassianWikiRenderer;
  }

  public String getAvatarURL() {
    return avatarURL;
  }

  public String getContextPath() {
    return contextPath;
  }

  public String getCurrentUserEmail() {
    return currentUser;
  }

  public Long getDateFromFormated() {
    return dateFromFormated;
  }

  public Long getDateToFormated() {
    return dateToFormated;
  }

  public HashMap<Integer, List<Object>> getDaySum() {
    return daySum;
  }

  private String getFormattedRedirectUrl() {
    String currentUserEncoded;
    try {
      currentUserEncoded = URLEncoder.encode(currentUser, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      currentUserEncoded = "";
    }
    return String.format(
        SELF_WITH_DATE_AND_USER_URL_FORMAT,
        dateFromFormated,
        dateToFormated,
        currentUserEncoded);
  }

  public boolean getHasBrowseUsersPermission() {
    return hasBrowseUsersPermission;
  }

  public String getIssueCollectorSrc() {
    return issueCollectorSrc;
  }

  public IssueRenderContext getIssueRenderContext() {
    return issueRenderContext;
  }

  public List<Pattern> getIssuesRegex() {
    return issuesRegex;
  }

  public String getMessage() {
    return message;
  }

  public HashMap<Integer, List<Object>> getMonthSum() {
    return monthSum;
  }

  public HashMap<Integer, List<Object>> getRealDaySum() {
    return realDaySum;
  }

  public HashMap<Integer, List<Object>> getRealMonthSum() {
    return realMonthSum;
  }

  public HashMap<Integer, List<Object>> getRealWeekSum() {
    return realWeekSum;
  }

  public ApplicationUser getUserPickerObject() {
    return userPickerObject;
  }

  public HashMap<Integer, List<Object>> getWeekSum() {
    return weekSum;
  }

  public List<EveritWorklog> getWorklogs() {
    return worklogs;
  }

  private void initCurrentUserIfNecessary() {
    if ("".equals(currentUser) || !hasBrowseUsersPermission) {
      JiraAuthenticationContext authenticationContext = ComponentAccessor
          .getJiraAuthenticationContext();
      currentUser = authenticationContext.getUser().getUsername();
      setUserPickerObjectBasedOnCurrentUser();
    }
  }

  private void initDatesIfNecessary() {
    if (dateFromFormated == null) {
      Calendar calendarFrom = Calendar.getInstance();
      calendarFrom.add(Calendar.WEEK_OF_MONTH, -1);
      dateFromFormated = calendarFrom.getTimeInMillis();
    }
    if (dateToFormated == null) {
      Calendar calendarTo = Calendar.getInstance();
      dateToFormated = calendarTo.getTimeInMillis();
    }
  }

  private boolean isRealWorklog(final EveritWorklog worklog) {
    boolean isRealWorklog = true;
    if (issuesRegex != null) {
      for (Pattern issuePattern : issuesRegex) {
        boolean issueMatches = issuePattern.matcher(worklog.getIssue()).matches();
        // if match not count in summary
        if (issueMatches) {
          isRealWorklog = false;
          break;
        }
      }
    }
    return isRealWorklog;
  }

  private boolean loadDataFromSession() {
    HttpSession session = getHttpSession();
    Object data = session.getAttribute(SESSION_KEY);

    if (!(data instanceof TimetrackerReportsSessionData)) {
      return false;
    }
    TimetrackerReportsSessionData timetrackerReportsSessionData =
        (TimetrackerReportsSessionData) data;
    currentUser = timetrackerReportsSessionData.currentUser;
    dateFromFormated = timetrackerReportsSessionData.dateFrom;
    dateToFormated = timetrackerReportsSessionData.dateTo;
    return true;
  }

  private void loadIssueCollectorSrc() {
    Properties properties = PropertiesUtil.getJttpBuildProperties();
    issueCollectorSrc = properties.getProperty(ISSUE_COLLECTOR_SRC);
  }

  private void loadPluginSettingAndParseResult() {
    PluginSettingsValues pluginSettingsValues = jiraTimetrackerPlugin
        .loadPluginSettings();
    setIssuesRegex(pluginSettingsValues.filteredSummaryIssues);
  }

  private void normalizeContextPath() {
    String path = getHttpRequest().getContextPath();
    if ((path.length() > 0) && "/".equals(path.substring(path.length() - 1))) {
      contextPath = path.substring(0, path.length() - 1);
    } else {
      contextPath = path;
    }
  }

  private Calendar parseDateFrom() throws IllegalArgumentException {
    String dateFromParam = getHttpRequest().getParameter(PARAM_DATEFROM);
    if ((dateFromParam != null) && !"".equals(dateFromParam)) {
      dateFromFormated = Long.valueOf(dateFromParam);
      Calendar parsedCalendarFrom = Calendar.getInstance();
      parsedCalendarFrom.setTimeInMillis(dateFromFormated);
      return parsedCalendarFrom;
    } else {
      throw new IllegalArgumentException(INVALID_START_TIME);
    }
  }

  private Calendar parseDateTo() throws IllegalArgumentException {
    String dateToParam = getHttpRequest().getParameter(PARAM_DATETO);
    if ((dateToParam != null) && !"".equals(dateToParam)) {
      dateToFormated = Long.valueOf(dateToParam);
      Calendar parsedCalendarTo = Calendar.getInstance();
      parsedCalendarTo.setTimeInMillis(dateToFormated);
      return parsedCalendarTo;
    } else {
      throw new IllegalArgumentException(INVALID_END_TIME);
    }
  }

  private boolean parseFeedback() {
    if (getHttpRequest().getParameter("sendfeedback") != null) {
      if (JiraTimetrackerUtil.loadAndCheckFeedBackTimeStampFromSession(getHttpSession())) {
        String feedBackValue = getHttpRequest().getParameter("feedbackinput");
        String ratingValue = getHttpRequest().getParameter("rating");
        String customerMail =
            JiraTimetrackerUtil.getCheckCustomerMail(getHttpRequest().getParameter("customerMail"));
        String feedBack = "";
        String rating = NOT_RATED;
        if (feedBackValue != null) {
          feedBack = feedBackValue.trim();
        }
        if (ratingValue != null) {
          rating = ratingValue;
        }
        String mailSubject = JiraTimetrackerUtil
            .createFeedbackMailSubject(JiraTimetrackerAnalytics.getPluginVersion());
        String mailBody =
            JiraTimetrackerUtil.createFeedbackMailBody(customerMail, rating, feedBack);
        jiraTimetrackerPlugin.sendEmail(mailSubject, mailBody);
      } else {
        message = FREQUENT_FEEDBACK;
      }
      return true;
    }
    return false;
  }

  private void readObject(final java.io.ObjectInputStream stream) throws IOException,
      ClassNotFoundException {
    stream.close();
    throw new java.io.NotSerializableException(getClass().getName());
  }

  private void saveDataToSession() {
    HttpSession session = getHttpSession();
    session.setAttribute(SESSION_KEY,
        new TimetrackerReportsSessionData().currentUser(currentUser).dateFrom(dateFromFormated)
            .dateTo(dateToFormated));
  }

  public void setAvatarURL(final String avatarURL) {
    this.avatarURL = avatarURL;
  }

  public void setContextPath(final String contextPath) {
    this.contextPath = contextPath;
  }

  public void setCurrentUser(final String currentUserEmail) {
    currentUser = currentUserEmail;
  }

  private void setCurrentUserFromParam() throws IllegalArgumentException {
    String selectedUser = getHttpRequest().getParameter(PARAM_USERPICKER);
    if (selectedUser == null) {
      throw new IllegalArgumentException(INVALID_USER_PICKER);
    }
    currentUser = selectedUser;
    if ("".equals(currentUser) || !hasBrowseUsersPermission) {
      JiraAuthenticationContext authenticationContext = ComponentAccessor
          .getJiraAuthenticationContext();
      currentUser = authenticationContext.getUser().getKey();
    }
  }

  public void setDateFromFormated(final Long dateFromFormated) {
    this.dateFromFormated = dateFromFormated;
  }

  public void setDateToFormated(final Long dateToFormated) {
    this.dateToFormated = dateToFormated;
  }

  public void setDaySum(final HashMap<Integer, List<Object>> daySum) {
    this.daySum = daySum;
  }

  public void setHasBrowseUsersPermission(final boolean hasBrowseUsersPermission) {
    this.hasBrowseUsersPermission = hasBrowseUsersPermission;
  }

  public void setIssuesRegex(final List<Pattern> issuesRegex) {
    this.issuesRegex = issuesRegex;
  }

  public void setMessage(final String message) {
    this.message = message;
  }

  public void setMonthSum(final HashMap<Integer, List<Object>> monthSum) {
    this.monthSum = monthSum;
  }

  public void setUserPickerObject(final ApplicationUser userPickerObject) {
    this.userPickerObject = userPickerObject;
  }

  private void setUserPickerObjectBasedOnCurrentUser() {
    if (!"".equals(currentUser)) {
      userPickerObject = ComponentAccessor.getUserUtil().getUserByName(currentUser);
      if (userPickerObject == null) {
        throw new IllegalArgumentException(INVALID_USER_PICKER);
      }
      AvatarService avatarService = ComponentAccessor.getComponent(AvatarService.class);
      setAvatarURL(avatarService.getAvatarURL(
          ComponentAccessor.getJiraAuthenticationContext().getUser(),
          userPickerObject, Avatar.Size.SMALL).toString());
    } else {
      userPickerObject = null;
    }
  }

  public void setWeekSum(final HashMap<Integer, List<Object>> weekSum) {
    this.weekSum = weekSum;
  }

  public void setWorklogs(final List<EveritWorklog> worklogs) {
    this.worklogs = worklogs;
  }

  private void validateDates(final Calendar startDate, final Calendar lastDate) {
    if (startDate.after(lastDate)) {
      throw new IllegalArgumentException(WRONG_DATES);
    }

    Calendar yearCheckCal = (Calendar) lastDate.clone();
    yearCheckCal.add(Calendar.YEAR, -1);
    if (startDate.before(yearCheckCal)) {
      throw new IllegalArgumentException(EXCEEDED_A_YEAR);
    }
  }

  private void writeObject(final java.io.ObjectOutputStream stream) throws IOException {
    stream.close();
    throw new java.io.NotSerializableException(getClass().getName());
  }
}

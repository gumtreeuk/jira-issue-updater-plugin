package info.bluefloyd.jenkins;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.bluefloyd.jira.model.IssueSummary;
import info.bluefloyd.jira.model.IssueSummaryList;
import info.bluefloyd.jira.model.RestResult;
import info.bluefloyd.jira.model.TransitionList;
import org.apache.commons.codec.binary.Base64;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import org.apache.commons.lang3.StringEscapeUtils;
import java.util.List;
import java.util.Map;

/**
 * Simple generic REST client based on native HTTP. Also contains a logic layer
 * to allow easy interaction with the JIRA REST API.
 *
 * @author Ian Sparkes, Swisscom AG
 */
public class RESTClient {

  // REST paths for the calls we want to make - suffixed onto the "restAPIUrl
  private static final String REST_SEARCH_PATH = "/search?jql";
  private static final String REST_ADD_COMMENT_PATH = "/issue/{issue-key}/comment";
  private static final String REST_UPDATE_STATUS_PATH = "/issue/{issue-key}/transitions";
  private static final String REST_UPDATE_FIELD_PATH = "/issue/{issue-key}";

  private final String baseAPIUrl;
  private final String userName;
  private final String password;
  private final PrintStream logger;
  private boolean debug = false;
  private final String basicAuthToken;

  // Constructor - set up required information
  public RESTClient(String baseAPIUrl, String userName, String password, PrintStream logger) throws UnsupportedEncodingException {
    this.baseAPIUrl = baseAPIUrl;
    this.userName = userName;
    this.password = password;
    this.logger = logger;

    String rawAuth = userName + ":" + password;
    Base64 encoder = new Base64();
    basicAuthToken = "Basic " + encoder.encodeAsString(rawAuth.getBytes("UTF-8"));
  }

  /**
   * Get back a minimal list of the issues we are interested in, as determined
   * by the given JQL. We only recover the first 10k issues, and more than that
   * is likely to be a mistake.
   *
   * An error in making the REST call or decoding the response results in a null
   * result.
   *
   * @param jql
   * @return The list of issues, null if exception, empty list if no matching
   * issues
   */
  public IssueSummaryList findIssuesByJQL(String jql) {
    String findIssueUrlString = baseAPIUrl + REST_SEARCH_PATH;
    if (isDebug()) {
      logger.println("***Using this URL for finding the issues: " + findIssueUrlString);
    }

    URL findIssueURL;
    try {
      findIssueURL = new URL(findIssueUrlString);
    } catch (MalformedURLException ex) {
      logger.println("Unable to parse URL string " + findIssueUrlString);
      logger.println(ex);
      return null;
    }

    String bodydata = "{"
            + "    \"jql\": \"" + StringEscapeUtils.escapeJson(jql) + "\",\n"
            + "    \"startAt\": 0,\n"
            + "    \"maxResults\": 10000,\n"
            + "    \"fields\": [\n"
            + "        \"summary\",\n"
            + "        \"versions\"\n"
            + "    ]\n"
            + "}";

    if (isDebug()) {
      logger.println("***Bodydata for search: ------------------------------" );
      logger.println(bodydata);
      logger.println("***Bodydata for search: ------------------------------" );
    }

    RestResult result;
    try {
      result = doPost(findIssueURL, bodydata);
    } catch (IOException ex) {
      logger.println("Unable to connect to REST service");
      logger.println(ex);
      return null;
    }

    if (isDebug()) {
      logger.println("***REST result:  " + result.getResultCode());
      logger.println("***REST message: " + result.getResultMessage());
    }

    if (result.getResultCode() == HttpURLConnection.HTTP_OK) {
      ObjectMapper mapper = new ObjectMapper();
      IssueSummaryList summaryList;
      try {
        summaryList = mapper.readValue(result.getResultMessage(), IssueSummaryList.class);
      } catch (IOException ex) {
        logger.println("Unable to parse JSON result: " + result.getResultMessage());
        logger.println(ex);
        return null;
      }

      return summaryList;
    } else {
      logger.println("Unable to find issues: (" + result.getResultCode() + ") " + result.getResultMessage());
      return null;
    }
  }

  /**
   * Update the status of a given issue.
   *
   * @param issue The issue we want to update
   * @param realWorkflowActionName The target status
   * @return False if operation fails, otherwise true
   */
  public Boolean updateIssueStatus(IssueSummary issue, String realWorkflowActionName) {
    String transitionPath = baseAPIUrl + REST_UPDATE_STATUS_PATH.replaceAll("\\{issue-key\\}", issue.getKey());
    if (isDebug()) {
      logger.println("***Using this URL for finding the transition: " + transitionPath);
    }

    URL transitionURL;
    try {
      transitionURL = new URL(transitionPath);
    } catch (MalformedURLException ex) {
      logger.println("Unable to parse URL string " + transitionPath);
      logger.println(ex);
      return false;
    }

    if (!realWorkflowActionName.trim().isEmpty()) {
      // Get possible transitions
      RestResult result;
      try {
        result = doGet(transitionURL);
      } catch (IOException ex) {
        logger.println("Unable to connect to REST service to check possible transitions");
        logger.println(ex);
        return false;
      }

      if (isDebug()) {
        logger.println("***REST result:  " + result.getResultCode());
        logger.println("***REST message: " + result.getResultMessage());
      }

      if (result.getResultCode() == HttpURLConnection.HTTP_OK) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        TransitionList possibleTransition;
        try {
          possibleTransition = mapper.readValue(result.getResultMessage(), TransitionList.class);
        } catch (IOException ex) {
          logger.println("Unable to parse JSON result: " + result.getResultMessage());
          logger.println(ex);
          return false;
        }

        if (possibleTransition.containsTransition(realWorkflowActionName)) {
          Integer targetTransitionId = possibleTransition.getTransitionId(realWorkflowActionName);
          String bodydata = "{\"transition\": {\"id\": \"" + targetTransitionId + "\"}}";
          if (isDebug()) {
            logger.println("*** sending data: " + bodydata);
          }

          try {
            result = doPost(transitionURL, bodydata);
          } catch (IOException ex) {
            logger.println("Unable to connect to REST service to perform transition");
            logger.println(ex);
            return false;
          }

          if (result.getResultCode() != HttpURLConnection.HTTP_NO_CONTENT) {
            logger.println("Could not update status for issue: " + issue.getKey() + " (" + result.getResultCode() + ") " + result.getResultMessage());
            return false;
          }
        } else {
          logger.println("Not possible to transtion " + issue.getKey() + " to status " + realWorkflowActionName + " because the transition is not possible");
          logger.println("Possible transtions:" + possibleTransition.getTransitionsToString());
          return false;
        }
      } else {
        logger.println("Unable to find transitions: (" + result.getResultCode() + ")" + result.getResultMessage());
        return false;
      }
    }
    return true;
  }

  /**
   * Add a comment to an issue.
   *
   * @param issue The issue to update
   * @param realComment The comment text to add
   */
  public Boolean addIssueComment(IssueSummary issue, String realComment) {

    String issuePath = baseAPIUrl + REST_ADD_COMMENT_PATH.replaceAll("\\{issue-key\\}", issue.getKey());
    if (isDebug()) {
      logger.println("***Using this URL for adding the comment: " + issuePath);
    }

    URL addCommentURL;
    try {
      addCommentURL = new URL(issuePath);
    } catch (MalformedURLException ex) {
      logger.println("Unable to parse URL string " + issuePath);
      logger.println(ex);
      return false;
    }

    if (!realComment.trim().isEmpty()) {
      String bodydata = "{\"body\": \"" + StringEscapeUtils.escapeJson(realComment) + "\"}";

      if (isDebug()) {
        logger.println("***Bodydata for add comment: ------------------------------" );
        logger.println(bodydata);
        logger.println("***Bodydata for add comment: ------------------------------" );
      }

      RestResult result;
      try {
        result = doPost(addCommentURL, bodydata);
      } catch (IOException ex) {
        logger.println("Unable to connect to REST service to add comment");
        logger.println(ex);
        return false;
      }

      if (isDebug()) {
        logger.println("***REST result:  " + result.getResultCode());
        logger.println("***REST message: " + result.getResultMessage());
      }

      if (result.getResultCode() != HttpURLConnection.HTTP_CREATED) {
        logger.println("Could not set comment " + realComment + " in issue " + issue.getKey() + " (" + result.getResultCode() + ") " + result.getResultMessage());
        return false;
      }
    }
    return true;
  }

  /**
   * Update the status of the given field to the given value.
   *
   * @param issue
   * @param customFieldId The field we are trying to change
   * @param realFieldValue The new value
   */
  public Boolean updateIssueField(IssueSummary issue, String customFieldId, String realFieldValue) {
    String setFieldsPath = baseAPIUrl + REST_UPDATE_FIELD_PATH.replaceAll("\\{issue-key\\}", issue.getKey());
    if (isDebug()) {
      logger.println("***Using this URL for updating issue field: " + setFieldsPath);
    }

    URL setFieldsURL;
    try {
      setFieldsURL = new URL(setFieldsPath);
    } catch (MalformedURLException ex) {
      logger.println("Unable to parse URL string " + setFieldsPath);
      logger.println(ex);
      return false;
    }

    if (!customFieldId.trim().isEmpty()) {
      String bodydata = "{\"fields\": {\"" + customFieldId + "\": \"" + StringEscapeUtils.escapeJson(realFieldValue) + "\"}}";
      if (isDebug()) {
        logger.println("***Bodydata for update field: ------------------------------" );
        logger.println(bodydata);
        logger.println("***Bodydata for update field: ------------------------------" );
      }

      RestResult result;
      try {
        result = doPut(setFieldsURL, bodydata);
      } catch (IOException ex) {
        logger.println("Unable to connect to REST service to set field ");
        logger.println(ex);
        return false;
      }

      if (result.getResultCode() != HttpURLConnection.HTTP_NO_CONTENT) {
        logger.println("Could not set field " + customFieldId + " in issue " + issue.getKey() + " (" + result.getResultCode() + ") " + result.getResultMessage());
        return false;
      }
    }
    return true;
  }

//  private void updateFixedVersions(IssueSummary issue, PrintStream logger) {
//    // NOT resettingFixedVersions and EMPTY fixedVersionNames: do not need to update the issue,
//    // otherwise:
//    if (resettingFixedVersions || !fixedVersionNames.isEmpty()) {
//
//      //Copy The Given Remote ID's to be applied to a local Variable. In case Old Versions should be kept we need to add them here
//      Collection<String> finalVersionIds = new HashSet<String>();
//      if (!fixedVersionNames.isEmpty()) {
//        finalVersionIds.addAll(mapFixedVersionNamesToIds(client, session, issue.getProject(), fixedVersionNames, logger));
//      }
//
//      // if not reset origin fixed versions, then also merge their IDs to the set.
//      if (!resettingFixedVersions) {
//        for (RemoteVersion ver : issue.getFixVersions()) {
//          finalVersionIds.add(ver.getId());
//        }
//      }
//      // do the update
//      boolean updateSuccessful = client.updateFixedVersions(session, issue, finalVersionIds);
//      if (!updateSuccessful) {
//        logger.println("Could not update fixed versions for issue: "
//                + issue.getKey() + " to " + finalVersionIds
//                + ". For details on the exact problem consult the Jenkins server logs.");
//      }
//    }
//  }
//  
//  /**
//   * Converts version names to IDs for the specified project. Non-existent
//   * versions are ignored, error messages are logged.
//   * <p>
//   * The jira soap api needs <code>ID</code>s of the fixed versions instead the
//   * human readable <code>name</code>s. The (fixed) versions are project
//   * specific. Since the issues found by <code>jql</code> do not necessarily
//   * belong to the same jira project. the versions must be retrieved for every
//   * single issue. In some cases, however, the issues do belong to the same
//   * project, so the Soap call to get versions may well be redundant. Those
//   * unnecessary soap calls may cause performance problem if number of issues is
//   * large. {@link #projectVersionNameIdCache} as a primitive cache, is intended
//   * to improve the situation (could this cause concurrent issues?).
//   * </p>
//   *
//   * @param session
//   * @param projectKey	key of the project
//   * @param versionNames	a collection of human readable jira version names (jira
//   * built-in or configured per project)
//   * @return	corresponding jira version ids
//   */
//  private Collection<String> mapFixedVersionNamesToIds(String projectKey, Collection<String> versionNames, PrintStream logger) {
//    // lazy fetching project versions and initializing the name-id map for the versions if necessary
//    Map<String, String> map = projectVersionNameIdCache.get(projectKey);
//    if (map == null) {
//      map = new ConcurrentHashMap<String, String>();
//      projectVersionNameIdCache.put(projectKey, map);
//      List<RemoteVersion> versions = client.getVersions(session, projectKey);
//      for (RemoteVersion ver : versions) {
//        map.put(ver.getName(), ver.getId());
//      }
//    }
//    // getting the ids corresponding to the names
//    Collection<String> ids = new HashSet<String>();
//    for (String name : versionNames) {
//      if (name != null) {
//        final String id = map.get(name.trim());
//        if (id == null) {
//          if (createNonExistingFixedVersions) {
//            logger.println("Creating Non-existent version " + name + " in project " + projectKey);
//            RemoteVersion newVersion = client.addVersion(session, projectKey, name);
//            if (newVersion.getId() != null) {
//              ids.add(newVersion.getId());
//              map.put(name, newVersion.getId());
//            } else {
//              logger.println("There was a problem creating Version " + name + " in project " + projectKey);
//            }
//          } else {
//            logger.println("Cannot find version " + name + " in project " + projectKey);
//          }
//        } else {
//          ids.add(id);
//        }
//      }
//    }
//    return ids;
//  }

  // ---------------------------------------------------------------------------
  // Generic REST call implementations
  // ---------------------------------------------------------------------------
  /**
   * Perform a GET action on the given URL with the credentials.
   *
   * @param url The full REST URL to use
   * @return The REST response
   * @throws IOException
   */
  private RestResult doGet(URL url) throws IOException {
      return doRequest("GET", url, null);
  }

  /**
   * Perform a POST action on the given URL with the credentials and body.
   *
   * @param url The full REST URL to use
   * @param bodydata The post body we are using
   * @return The REST response
   * @throws IOException
   */
  private RestResult doPost(URL url, String bodydata) throws IOException {
      return doRequest("POST", url, bodydata);
  }

  /**
   * Perform a PUT action on the given URL with the credentials and body
   *
   * @param url The full REST URL to use
   * @param bodydata The post body we are using
   * @return The REST response
   * @throws IOException
   */
  private RestResult doPut(URL url, String bodydata) throws IOException {
      return doRequest("PUT", url, bodydata);
  }
  
  /**
   * Perform a given action on the given URL with the credentials and body.
   *
   * @param action Action to perform (GET, POST, PUT)
   * @param url The full REST URL to use
   * @param bodydata The post body we are using
   * @return The REST response
   * @throws IOException
   */
  private RestResult doRequest(String action, URL url, String bodydata) throws IOException {

    RestResult result = new RestResult();
    byte[] postDataBytes = null;

    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod(action);
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.addRequestProperty("Authorization", basicAuthToken);
    
    if (bodydata != null) {
        postDataBytes = bodydata.getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
        conn.setDoOutput(true);
    }
    
    if (isDebug()) {
      logger.println("***REST " + action + ":");
      logger.println("***Auth: " + basicAuthToken);
      Map<String,List<String>> properties = conn.getRequestProperties();
      logger.println("***REST property size: " + properties.size());
      for(String property : properties.keySet()) {
        logger.println("***REST property: " + property + " --> " + properties.get(property).toString());
      }
    }

    if (!"GET".equals(action)) {
        OutputStream os = conn.getOutputStream();
        os.write(postDataBytes);
        os.flush();   
    }

    if (isDebug()) {
        logger.println("***Response code: " + conn.getResponseCode());
    }
    
    result.setResultCode(conn.getResponseCode());
    result.setResultMessage(getOutput(conn));

    conn.disconnect();

    return result;
  }

  private String getOutput(HttpURLConnection conn) throws IOException {
    InputStream responseStream;
    if (conn.getResponseCode() >= 400) {
      responseStream = conn.getErrorStream();
    } else {
      responseStream = conn.getInputStream();
    }
    BufferedReader br = new BufferedReader(new InputStreamReader(responseStream));

    StringBuilder output = new StringBuilder();
    String outputLine;
    while ((outputLine = br.readLine()) != null) {
      output.append(outputLine);
    }
    return output.toString();
  }

  /**
   * @return the debug
   */
  public boolean isDebug() {
    return debug;
  }

  /**
   * @param debug the debug to set
   */
  public void setDebug(boolean debug) {
    this.debug = debug;
  }
}

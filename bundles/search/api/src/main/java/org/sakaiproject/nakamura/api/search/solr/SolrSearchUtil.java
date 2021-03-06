/**
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.nakamura.api.search.solr;

import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.DEFAULT_PAGED_ITEMS;
import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.INFINITY;
import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.PARAMS_ITEMS_PER_PAGE;
import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.PARAMS_PAGE;
import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.TIDY;

import org.apache.commons.lang.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.request.RequestParameter;
import org.sakaiproject.nakamura.api.search.SearchUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class SolrSearchUtil {

  public static final Logger LOGGER = LoggerFactory.getLogger(SolrSearchUtil.class);


  public static long[] getOffsetAndSize(SlingHttpServletRequest request,
      final Map<String, Object> options) {
    long nitems;
    if (options != null && options.get(PARAMS_ITEMS_PER_PAGE) != null) {
      nitems = Long.parseLong(String.valueOf(options.get(PARAMS_ITEMS_PER_PAGE)));
    } else {
      nitems = SolrSearchUtil.longRequestParameter(request, PARAMS_ITEMS_PER_PAGE,
          DEFAULT_PAGED_ITEMS);
    }
    long page;
    if (options != null && options.get(PARAMS_PAGE) != null) {
      page = Long.parseLong(String.valueOf(options.get(PARAMS_PAGE)));
    } else {
      page = SolrSearchUtil.longRequestParameter(request, PARAMS_PAGE, 0);
    }
    long offset = page * nitems;
    return new long[]{offset, nitems };
  }


  /**
   * Check for an integer value in the request.
   *
   * @param request
   *          The request to look in.
   * @param paramName
   *          The name of the parameter that holds the integer value.
   * @param defaultVal
   *          The default value in case the parameter is not found or is not an integer
   * @return The long value.
   */
  public static long longRequestParameter(SlingHttpServletRequest request,
      String paramName, long defaultVal) {
    RequestParameter param = request.getRequestParameter(paramName);
    if (param != null) {
      try {
        return Integer.parseInt(param.getString());
      } catch (NumberFormatException e) {
        LOGGER.warn(paramName + " parameter (" + param.getString()
            + ") is invalid; defaulting to " + defaultVal);
      }
    }
    return defaultVal;
  }

  public static int getTraversalDepth(SlingHttpServletRequest req) {
    int maxRecursionLevels = 0;
    final String[] selectors = req.getRequestPathInfo().getSelectors();
    if (selectors != null && selectors.length > 0) {
      final String level = selectors[selectors.length - 1];
      if (!TIDY.equals(level)) {
        if (INFINITY.equals(level)) {
          maxRecursionLevels = -1;
        } else {
          try {
            maxRecursionLevels = Integer.parseInt(level);
          } catch (NumberFormatException nfe) {
            LOGGER.warn("Invalid recursion selector value '" + level
                + "'; defaulting to 0");
          }
        }
      }
    }
    return maxRecursionLevels;
  }


  /**
   * Perform a MoreLikeThis query, retrieve the IDs of matching documents, and
   * return a list of document IDs weighted relatively to their similarity
   * score.
   *
   * @param mltQuery
   *          A MoreLikeThis query (e.g.: "type:g AND readers:myuser")
   * @param options
   *          An even number of strings that will be passed as options to the MoreLikeThis query.
   * @return A new Solr query string that yields the matching similar documents in order of
   *         similarity (suitable for use in a query template)
   */
  public static String getMoreLikeThis(SlingHttpServletRequest request,
                                       SolrSearchServiceFactory searchService,
                                       String mltQuery,
                                       String... options)
    throws SolrSearchException
  {
    if ((options.length % 2) != 0) {
      throw new IllegalArgumentException("The number of getMoreLikeThis options must be even.");
    }

    Map<String,Object> mltOptions = new HashMap<String,Object>();
    for (int i = 0; i < options.length; i += 2) {
      mltOptions.put(options[i], options[i + 1]);
    }

    SolrSearchResultSet moreLikeThat =
      searchService.getSearchResultSet(request,
                                       new org.sakaiproject.nakamura.api.search.solr.Query(mltQuery,
                                                                                           mltOptions));

    List<String> suggestedIds = new ArrayList<String>();

    Iterator<Result> resultIterator = moreLikeThat.getResultSetIterator();

    // Assign a descending weight to each matched ID to preserve the original
    // score ordering.
    long weight = (moreLikeThat.getSize() + 1);

    while (resultIterator.hasNext()) {
      Result result = resultIterator.next();
      Map<String, Collection<Object>> props = result.getProperties();

      for (Object id : props.get("id")) {
        suggestedIds.add("\"" +
                         SearchUtil.escapeString((String) id,
                                                 org.sakaiproject.nakamura.api.search.solr.Query.SOLR) +
                         "\"^" +
                         weight * 100);
        weight--;
      }
    }

    if (suggestedIds.size() > 0) {
      return "id:(" + StringUtils.join(suggestedIds, " OR ") + ")";
    } else {
      return null;
    }
  }


  public static Iterator<Result> getRandomResults(SlingHttpServletRequest request,
                                                  SolrSearchResultProcessor searchProcessor,
                                                  String query,
                                                  String... options)
    throws SolrSearchException
  {
    if ((options.length % 2) != 0) {
      throw new IllegalArgumentException("The number of getRandomResults options must be even.");
    }

    final Map<String,Object> queryOptions = new HashMap<String,Object>();
    for (int i = 0; i < options.length; i += 2) {
      queryOptions.put(options[i], options[i + 1]);
    }

    // random solr sorting requires a seed for the dynamic random_* field
    final int random = (int) (Math.random() * 10000);
    queryOptions.put("sort", "random_" + random + " desc");

    SolrSearchResultSet rs = null;
    try {
      rs = searchProcessor.getSearchResultSet
        (request,
         new org.sakaiproject.nakamura.api.search.solr.Query(org.sakaiproject.nakamura.api.search.solr.Query.SOLR,
                                                             query,
                                                             queryOptions));

      if (rs == null) {
        return null;
      }

      return rs.getResultSetIterator();

    } catch (SolrSearchException e) {
      LOGGER.error(e.getLocalizedMessage(), e);
      throw new IllegalStateException(e);
    }
  }

  public static SolrSearchParameters getParametersFromRequest (final SlingHttpServletRequest request) {
    final SolrSearchParameters params = new SolrSearchParameters();

    params.setPage(SolrSearchUtil.longRequestParameter(request, PARAMS_PAGE, 0));
    params.setRecordsPerPage(SolrSearchUtil.longRequestParameter(request, PARAMS_ITEMS_PER_PAGE, DEFAULT_PAGED_ITEMS));
    params.setPath(request.getResource().getPath());

    return params;
  }

}

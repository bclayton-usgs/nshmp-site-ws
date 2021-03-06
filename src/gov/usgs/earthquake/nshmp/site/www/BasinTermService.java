package gov.usgs.earthquake.nshmp.site.www;

import static com.google.common.base.Strings.isNullOrEmpty;
import static gov.usgs.earthquake.nshmp.site.www.BasinUtil.GSON;
import static gov.usgs.earthquake.nshmp.www.Util.readDouble;
import static gov.usgs.earthquake.nshmp.www.Util.readValue;
import static gov.usgs.earthquake.nshmp.www.meta.Metadata.errorMessage;

import java.io.IOException;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import gov.usgs.earthquake.nshmp.geo.Location;
import gov.usgs.earthquake.nshmp.site.www.ArcGis.ArcGisResult;
import gov.usgs.earthquake.nshmp.site.www.BasinUtil.Key;
import gov.usgs.earthquake.nshmp.site.www.basin.Basin;
import gov.usgs.earthquake.nshmp.site.www.basin.BasinData;
import gov.usgs.earthquake.nshmp.site.www.basin.BasinModel;
import gov.usgs.earthquake.nshmp.site.www.basin.BasinValues;
import gov.usgs.earthquake.nshmp.site.www.basin.BasinValues.BasinValue;
import gov.usgs.earthquake.nshmp.site.www.basin.Basins;
import gov.usgs.earthquake.nshmp.site.www.basin.Basins.BasinRegion;
import gov.usgs.earthquake.nshmp.util.Maths;
import gov.usgs.earthquake.nshmp.www.NshmpServlet;
import gov.usgs.earthquake.nshmp.www.meta.EnumParameter;
import gov.usgs.earthquake.nshmp.www.meta.ParamType;
import gov.usgs.earthquake.nshmp.www.meta.Status;

/**
 * Basin term service to return z1p0 and z2p5 values.
 * 
 * The basin term service can return values from the ArcGIS service
 * (/basin/arc-data) or by using the local files (/basin/local-data) in the data
 * directory.
 * 
 * <p> Note: When using the ArcGis service route the host server needs to be
 * identified in a config.properties file at the root of the source directory,
 * for example: {@code arcgis_host=https://some.agol.server}
 * 
 * <p> Note: If the latitude and longitude supplied in the query is not
 * contained in a basin region the resulting z1p0 and z2p5 values are set to
 * null.
 * 
 * <p> Note: Supplied latitude and longitudes are rounded to the nearest
 * {@code 0.01}, which is the resolution of the underlying basin depth data.
 * 
 * @author Brandon Clayton
 */
@SuppressWarnings("unused")
@WebServlet(
    name = "Basin Term Service",
    description = "Utility for getting basin terms",
    urlPatterns = {
        "/basin",
        "/basin/*" })
public class BasinTermService extends NshmpServlet {

  private static final Basins BASINS = Basins.getBasins();

  private static final BasinData BASIN_DATA = BasinData.readBasinData(BASINS);

  private static final String SERVICE_NAME = "Basin Term Service";

  private static final String SERVICE_DESCRIPTION = "Get basin terms";

  private static final String SERVICE_SYNTAX = "%s://%s/nshmp-site-ws/basin" +
      "?latitude={latitude}&longitude={longitude}&model={basinModel}";

  @Override
  protected void doGet(
      HttpServletRequest request,
      HttpServletResponse response)
      throws ServletException, IOException {

    UrlHelper urlHelper = NshmpServlet.urlHelper(request, response);
    String pathInfo = request.getPathInfo();
    String query = request.getQueryString();

    try {
      if (!isNullOrEmpty(pathInfo) && pathInfo.equals("/geojson")) {
        response.getWriter().print(BASINS.json());
      } else if (!isNullOrEmpty(query) && pathInfo.equals("/local-data")) {
        Response svcResponse = processBasinTermWithLocalData(request, urlHelper);
        String json = GSON.toJson(svcResponse);
        urlHelper.writeResponse(json);
      } else if (!isNullOrEmpty(query) && pathInfo.equals("/arc-data")) {
        Response svcResponse = processBasinTermWithArcGIS(request, urlHelper);
        String json = GSON.toJson(svcResponse);
        urlHelper.writeResponse(json);
      } else {
        final String usage = GSON.toJson(new Metadata());
        urlHelper.writeResponse(usage);
      }
    } catch (Exception e) {
      e.printStackTrace();
      response.getWriter().print(errorMessage(urlHelper.url, e, false));
    }
  }

  /**
   * Process request using local basin data.
   */
  private Response processBasinTermWithLocalData(HttpServletRequest request, UrlHelper urlHelper) {
    RequestData requestData = buildRequest(request, BasinData.BASIN_DATA_SPACING);

    if (requestData.basinRegion == null) {
      return processNullResult(requestData, urlHelper);
    }

    Location loc = Location.create(requestData.latitude, requestData.longitude);
    Basin basin = Basin.fromId(requestData.basinRegion.id);
    BasinValues basinValues = BASIN_DATA.getBasinValues(basin, loc);

    return new Response(requestData, basinValues, urlHelper);
  }

  /**
   * Process request using ArcGIS service.
   */
  private Response processBasinTermWithArcGIS(HttpServletRequest request, UrlHelper urlHelper) {
    RequestData requestData = buildRequest(request, ArcGis.ROUND_MODEL);

    if (requestData.basinRegion == null) {
      return processNullResult(requestData, urlHelper);
    }

    ArcGisResult arcGisResult = ArcGis.callPointService(
        requestData.latitude,
        requestData.longitude);

    Double z2p5 = arcGisResult.basinModels.get(requestData.basinModel.z2p5) / 1000.0;
    Double z1p0;
    Basin basin = Basin.fromId(requestData.basinRegion.id);

    /*
     * Seattle is a special case where z1p0 is returned as a converted z2p5
     * value, instead of the model value itself. Two regressions derived by M.
     * Moschetti in memo dated July 6, 2018, each with 50% weight.
     * 
     * TODO (revisit) There is also a problem right now in that the Seattle z1p0
     * and z2p5 datasets do not have identical spatial representation in the Arc
     * geodatabase. The basin polygon was created based on the z2p5 dataset, but
     * there are locations (e.g. -122.7 46.9) where the z1p0 value will be null.
     * The site being in the Seattle polygon expects both values to be valid but
     * throws an error when they are not. For the time being we bypass this
     * error by handling processing the z2p5, regardless of basin, and only then
     * process z1p0.
     */
    if (basin.equals(Basin.PUGET_LOWLAND) && z2p5 != null) {
      z1p0 =
          0.5 * (0.1146 * z2p5 + 0.2826) +
              0.5 * (0.0933 * z2p5 + 0.1444);
    } else {
      z1p0 = arcGisResult.basinModels.get(requestData.basinModel.z1p0) / 1000.0;
    }

    BasinValue z1p0resp = new BasinValue(requestData.basinModel.z1p0, z1p0);
    BasinValue z2p5resp = new BasinValue(requestData.basinModel.z2p5, z2p5);

    BasinValues responseData = new BasinValues(z1p0resp, z2p5resp);

    return new Response(requestData, responseData, urlHelper);
  }

  private static Response processNullResult(
      RequestData requestData,
      UrlHelper urlHelper) {
    BasinValue z1p0 = new BasinValue("", null);
    BasinValue z2p5 = new BasinValue("", null);

    BasinValues responseData = new BasinValues(z1p0, z2p5);

    return new Response(requestData, responseData, urlHelper);
  }

  private static RequestData buildRequest(HttpServletRequest request, double roundTo) {
    double latitude = readDouble(Key.LATITUDE, request);
    double longitude = readDouble(Key.LONGITUDE, request);

    latitude = Maths.round(latitude, roundTo);
    longitude = Maths.round(longitude, roundTo);

    BasinRegion basinRegion = BASINS.findRegion(latitude, longitude);

    BasinModel basinModel = basinRegion == null ? null : getBasinModel(basinRegion, request);

    return new RequestData(basinRegion, basinModel, latitude, longitude);
  }

  private static BasinModel getBasinModel(
      BasinRegion basinRegion,
      HttpServletRequest request) {
    boolean hasBasinModel = request.getParameter(Key.MODEL.toString()) != null;

    return hasBasinModel ? BasinModel.fromId(readValue(Key.MODEL, request))
        : basinRegion.defaultModel;
  }

  private static class RequestData {
    final double latitude;
    final double longitude;
    final BasinModel basinModel;
    final BasinRegionRequest basinRegion;

    RequestData(
        BasinRegion basinRegion,
        BasinModel basinModel,
        double latitude,
        double longitude) {
      this.basinRegion = BasinRegionRequest.getBasinRegionRequest(basinRegion);
      this.basinModel = basinModel;
      this.latitude = latitude;
      this.longitude = longitude;
    }
  }

  private static class BasinRegionRequest {
    final String title;
    final String id;

    BasinRegionRequest(BasinRegion basinRegion) {
      title = basinRegion.title;
      id = basinRegion.basin.id;
    }

    private static BasinRegionRequest getBasinRegionRequest(BasinRegion basinRegion) {
      return basinRegion == null ? null : new BasinRegionRequest(basinRegion);
    }
  }

  static class Response {
    final String status;
    final String name;
    final String date;
    final String url;
    final RequestData request;
    final BasinValues response;

    Response(
        RequestData requestData,
        BasinValues responseData,
        UrlHelper urlHelper) {
      this.status = Status.SUCCESS.toString();
      this.name = SERVICE_NAME;
      this.date = new Date().toString();
      this.url = urlHelper.url;
      this.request = requestData;
      this.response = responseData;
    }
  }

  private static class Metadata {
    final String status;
    final String name;
    final String description;
    final String syntax;
    final EnumParameter<BasinModel> basinModels;
    final List<BasinRegion> basinRegions;

    Metadata() {
      this.status = Status.USAGE.toString();
      this.name = SERVICE_NAME;
      this.description = SERVICE_DESCRIPTION;
      this.syntax = SERVICE_SYNTAX;

      this.basinModels = new EnumParameter<>(
          "Basin models",
          ParamType.STRING,
          EnumSet.allOf(BasinModel.class));

      this.basinRegions = BASINS.basinRegions();
    }
  }

}

package com.cedexis.androidradar;

import android.util.Log;
import android.util.Pair;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RadarProbe {
    private static final int STANDARD_TIMEOUT = 4000;

    private int _probeType;
    private String _url;
    private RadarSession _session;
    private JSONObject _providerData;
    private String _baseUrl;
    private int _objectType;

    public RadarProbe(RadarSession session, JSONObject probeData, int probeType, JSONObject providerData) throws JSONException {
        _session = session;
        _probeType = probeType;
        _providerData = providerData;
        _baseUrl = probeData.getString("u");
        _objectType = probeData.getInt("t");
    }

    public String makeUrl() throws RadarClientException, JSONException {
        if (9 == _objectType) {
            throw new RadarClientException("DNS measurement type not implemented");
        }
        StringBuilder queryString = new StringBuilder("?rnd=");
        queryString.append(_probeType);
        queryString.append("-");
        queryString.append(_session.get_requestorZoneId());
        queryString.append("-");
        queryString.append(_session.get_requestorCustomerId());
        queryString.append("-");
        queryString.append(_providerData.getInt("z"));
        queryString.append("-");
        queryString.append(_providerData.getInt("c"));
        queryString.append("-");
        queryString.append(_providerData.getInt("i"));
        queryString.append("-");
        queryString.append(_session.get_transactionId());
        queryString.append("-");
        queryString.append(_session.get_requestSignature());
        return _baseUrl + queryString.toString();
    }

    public long calculateThroughput(long elapsed) {
        Pattern p = Pattern.compile("-(\\d+)kb", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(_baseUrl);
        // Eventually this number will be sent with the provider configuration, but for now we
        // must get it from the filename
        int fileSize = 100;
        if (m.find()) {
            fileSize = Integer.parseInt(m.group(1));
        }
        return Math.round(Math.floor(8 * 1000 * fileSize / elapsed));
    }

    public boolean measure(List<Pair<String, String>> progressData) throws JSONException {
        //Log.d(TAG, probe.toString());
        URL url = null;
        try {
            url = new URL(makeUrl());
        } catch (MalformedURLException | RadarClientException e) {
            e.printStackTrace();
            return false;
        }
        //Log.v(TAG, String.format("Probe URL: %s", url));

        int resultCode = 0;
        long measurement;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
//            connection.setConnectTimeout(10);
//            connection.setReadTimeout(10);
            Date startedAt = new Date();
            Date endedAt;
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            switch (_probeType) {
                case 1:
                case 0:
                    reader.readLine();
                    endedAt = new Date();
                    break;
                case 14:
                    while (null != reader.readLine()) {
                        continue;
                    }
                    endedAt = new Date();
                    break;
                default:
                    throw new RadarClientException(String.format("Unexpected probe type id: %d", _probeType));
            }
            long elapsed = endedAt.getTime() - startedAt.getTime();
            //Log.d(TAG, String.format("Elapsed: %d", elapsed));
            if (elapsed <= STANDARD_TIMEOUT) {
                if (ProbeType.THROUGHPUT == _probeType) {
                    measurement = calculateThroughput(elapsed);
                } else {
                    measurement = elapsed;
                }
            } else {
                resultCode = 1;
                measurement = 0;
            }
            //Log.d(TAG, String.format("Measurement: %d", measurement));
        } catch (IOException e) {
            resultCode = 1;
            measurement = 0;
        } catch (RadarClientException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (null != connection) {
                connection.disconnect();
            }
        }

        // Reports
        URL reportUrl = null;
        try {
            reportUrl = new URL(makeReportUrl(_session, _probeType, _providerData, measurement, resultCode));
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return false;
        }
        Log.d(RadarSessionTask.TAG, String.format("Report URL: %s", reportUrl));
        List<Pair<String, String>> headers = new ArrayList<>();
        headers.add(Pair.create("cedexis-android-network-type", _session.get_networkType()));
        headers.add(Pair.create("cedexis-android-network-subtype", _session.get_networkSubtype()));
        try {
            RadarSessionTask.makeHttpRequest(reportUrl, headers);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        String probeType = "unknown";
        String units = "ms";
        switch (_probeType) {
            case ProbeType.COLD:
                probeType = "connect";
                break;
            case ProbeType.RTT:
                probeType = "rtt";
                break;
            case ProbeType.THROUGHPUT:
                probeType = "throughput";
                units = "kbps";
                break;
        }
        String measurementKey = String.format("measurement.%s", probeType);
        if (0 != resultCode) {
            progressData.add(Pair.create(measurementKey, "error"));
            return false;
        }
        progressData.add(Pair.create(measurementKey, String.format("%d %s", measurement, units)));
        return true;
    }

    public String makeReportUrl(RadarSession session, int probeTypeId, JSONObject providerData, long measurement, int resultCode) throws JSONException {
        StringBuilder result = new StringBuilder("http://");
        result.append(RadarSessionTask.REPORT_DOMAIN);
        result.append("/f1/");
        result.append(session.get_requestSignature());
        result.append("/");
        result.append(providerData.getInt("z"));
        result.append("/");
        result.append(providerData.getInt("c"));
        result.append("/");
        result.append(providerData.getInt("i"));
        result.append("/");
        result.append(probeTypeId);
        result.append("/");
        result.append(resultCode);
        result.append("/");
        result.append(measurement);
        return result.toString();
    }
}

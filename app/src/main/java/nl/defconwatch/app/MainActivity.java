package nl.defconwatch.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String USGS_URL = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_day.geojson";
    private static final String GDACS_URL = "https://www.gdacs.org/xml/rss.xml";
    private static final String ISS_URL = "https://api.wheretheiss.at/v1/satellites/25544";
    private static final long REFRESH_MS = 10L * 60L * 1000L;
    private static final String PREFS = "defconwatch";
    private static final String CACHE_KEY = "incident_cache_v23";
    private static final String CACHE_TIME = "incident_cache_time_v23";
    private static final String CHANNEL_ID = "critical_incidents";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Incident> incidents = new ArrayList<>();
    private final Map<String, TextView> regionViews = new HashMap<>();

    private WorldMapView mapView;
    private TextView levelNumber;
    private TextView levelLabel;
    private TextView statusText;
    private TextView lastUpdated;
    private LinearLayout incidentContainer;
    private ProgressBar progress;
    private Button refreshButton;
    private String activeFilter = "ALLES";

    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            refreshData(false);
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();
        requestNotificationPermission();
        setContentView(buildUi());
        loadCachedData();
        refreshData(false);
        handler.postDelayed(periodicRefresh, REFRESH_MS);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(12), pad, pad);
        root.setBackgroundColor(Color.rgb(5, 7, 10));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("DEFCONWATCH", 24, Color.rgb(237,245,250), true));
        titles.addView(text("PUBLIC OSINT COMMAND CENTER • v2.3", 10, Color.rgb(66,211,255), true));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        refreshButton = new Button(this);
        refreshButton.setText("↻ LIVE");
        refreshButton.setTextColor(Color.BLACK);
        refreshButton.setBackgroundColor(Color.rgb(66,211,255));
        refreshButton.setOnClickListener(v -> refreshData(true));
        header.addView(refreshButton, new LinearLayout.LayoutParams(dp(96), dp(46)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(30));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout readiness = panel();
        readiness.addView(text("GLOBAL READINESS INDEX", 12, Color.rgb(143,166,181), true));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        levelNumber = text("4", 72, Color.rgb(255,213,74), true);
        levelLabel = text("ELEVATED AWARENESS\nUNOFFICIAL OSINT ESTIMATE", 15, Color.WHITE, true);
        row.addView(levelNumber, new LinearLayout.LayoutParams(dp(100), dp(100)));
        row.addView(levelLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        readiness.addView(row);
        readiness.addView(text("Geen officiële of geclassificeerde DEFCON-feed. De index wordt uitsluitend afgeleid uit openbare incidentgegevens.", 11, Color.rgb(143,166,181), false));
        content.addView(readiness);

        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = new LinearLayout(this);
        String[] filterNames = {"ALLES", "KRITIEK", "AARDBEVING", "STORM", "VULKAAN", "OVERSTROMING", "BOSBRAND", "SPACE", "RAMP"};
        for (String filter : filterNames) {
            Button button = new Button(this);
            button.setText(filter);
            button.setTextSize(11);
            button.setTextColor(Color.WHITE);
            button.setBackgroundColor(Color.rgb(23,35,46));
            button.setOnClickListener(v -> {
                activeFilter = filter;
                renderIncidents();
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            bp.setMargins(0, dp(10), dp(8), 0);
            filterRow.addView(button, bp);
        }
        filters.addView(filterRow);
        content.addView(filters);

        FrameLayout mapPanel = new FrameLayout(this);
        mapPanel.setBackgroundColor(Color.rgb(16,24,32));
        mapPanel.setPadding(dp(6), dp(6), dp(6), dp(6));
        mapView = new WorldMapView(this);
        mapView.setMarkerClickListener(this::showIncidentDialog);
        mapPanel.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270)));
        progress = new ProgressBar(this);
        mapPanel.addView(progress, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(282));
        mp.setMargins(0, dp(12), 0, 0);
        content.addView(mapPanel, mp);

        LinearLayout statusPanel = panel();
        statusText = text("Feeds worden geladen…", 13, Color.rgb(237,245,250), false);
        lastUpdated = text("Nog niet gesynchroniseerd", 11, Color.rgb(143,166,181), false);
        statusPanel.addView(statusText);
        statusPanel.addView(lastUpdated);
        content.addView(statusPanel);

        TextView regionsTitle = text("REGIONALE SIGNALEN", 13, Color.rgb(66,211,255), true);
        regionsTitle.setPadding(0, dp(16), 0, dp(6));
        content.addView(regionsTitle);
        String[] regions = {"Europa", "Noord-Amerika", "Latijns-Amerika", "Midden-Oosten", "Afrika", "Azië-Pacific"};
        for (String region : regions) {
            LinearLayout r = panel();
            r.addView(text(region, 14, Color.WHITE, true));
            TextView count = text("0 actieve signalen", 12, Color.rgb(143,166,181), false);
            r.addView(count);
            regionViews.put(region, count);
            content.addView(r);
        }

        TextView incidentsTitle = text("LAATSTE INCIDENTEN", 13, Color.rgb(66,211,255), true);
        incidentsTitle.setPadding(0, dp(16), 0, dp(6));
        content.addView(incidentsTitle);
        incidentContainer = new LinearLayout(this);
        incidentContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(incidentContainer);

        TextView sources = text("LIVE BRONNEN: USGS • GDACS • ISS\nTik op een kaartmarker voor details of op een incident om de originele bron te openen. Cache wordt lokaal bewaard voor offline weergave.", 10, Color.rgb(143,166,181), false);
        sources.setPadding(0, dp(18), 0, 0);
        content.addView(sources);
        return root;
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(12), dp(14), dp(12));
        p.setBackgroundColor(Color.rgb(16,24,32));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        p.setLayoutParams(lp);
        return p;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.12f);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private void refreshData(boolean manual) {
        refreshButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusText.setText(manual ? "Handmatige live-synchronisatie…" : "Live feeds worden opgehaald…");
        executor.execute(() -> {
            List<Incident> fetched = new ArrayList<>();
            StringBuilder errors = new StringBuilder();
            try { fetched.addAll(fetchUsgs()); } catch (Exception e) { errors.append("USGS niet bereikbaar. "); }
            try { fetched.addAll(fetchGdacs()); } catch (Exception e) { errors.append("GDACS niet bereikbaar. "); }
            try { fetched.add(fetchIss()); } catch (Exception e) { errors.append("ISS-feed niet bereikbaar. "); }
            Collections.sort(fetched, Comparator.comparingInt((Incident i) -> i.severity).reversed());
            String errorText = errors.toString();
            handler.post(() -> applyData(fetched, errorText));
        });
    }

    private List<Incident> fetchUsgs() throws Exception {
        HttpURLConnection connection = open(USGS_URL);
        String json;
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) { json = readAll(in); }
        finally { connection.disconnect(); }
        JSONObject root = new JSONObject(json);
        JSONArray features = root.getJSONArray("features");
        List<Incident> result = new ArrayList<>();
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject props = feature.getJSONObject("properties");
            JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");
            double mag = props.optDouble("mag", 0);
            Incident x = new Incident();
            x.type = "AARDBEVING";
            x.title = "M" + String.format(Locale.US, "%.1f", mag) + " • " + props.optString("place", "Onbekende locatie");
            x.lon = coords.getDouble(0);
            x.lat = coords.getDouble(1);
            x.severity = mag >= 7 ? 5 : mag >= 6 ? 4 : mag >= 5 ? 3 : 2;
            x.source = "USGS";
            x.url = props.optString("url", "https://earthquake.usgs.gov/");
            result.add(x);
        }
        return result;
    }

    private List<Incident> fetchGdacs() throws Exception {
        HttpURLConnection connection = open(GDACS_URL);
        List<Incident> result = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(in, "UTF-8");
            String title = null, link = null, description = null;
            boolean inItem = false;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("item".equalsIgnoreCase(tag)) inItem = true;
                    else if (inItem && "title".equalsIgnoreCase(tag)) title = parser.nextText();
                    else if (inItem && "link".equalsIgnoreCase(tag)) link = parser.nextText();
                    else if (inItem && "description".equalsIgnoreCase(tag)) description = parser.nextText();
                } else if (event == XmlPullParser.END_TAG && "item".equalsIgnoreCase(tag)) {
                    Incident x = new Incident();
                    x.title = title == null ? "GDACS-melding" : title;
                    String probe = ((title == null ? "" : title) + " " + (description == null ? "" : description)).toLowerCase(Locale.ROOT);
                    x.type = classifyGdacsType(probe);
                    x.severity = probe.contains("red") ? 5 : probe.contains("orange") ? 4 : 3;
                    x.source = "GDACS";
                    x.url = link == null ? "https://www.gdacs.org/" : link;
                    double[] pos = roughCoordinates(probe);
                    x.lat = pos[0]; x.lon = pos[1];
                    result.add(x);
                    title = link = description = null;
                    inItem = false;
                }
                event = parser.next();
            }
        } finally { connection.disconnect(); }
        return result;
    }


    private Incident fetchIss() throws Exception {
        HttpURLConnection connection = open(ISS_URL);
        String json;
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) { json = readAll(in); }
        finally { connection.disconnect(); }
        JSONObject root = new JSONObject(json);
        Incident x = new Incident();
        x.type = "SPACE";
        x.title = "ISS live positie • " + String.format(Locale.US, "%.2f, %.2f", root.optDouble("latitude", 0), root.optDouble("longitude", 0));
        x.lat = root.optDouble("latitude", 0);
        x.lon = root.optDouble("longitude", 0);
        x.severity = 1;
        x.source = "Where The ISS At";
        x.url = "https://wheretheiss.at/";
        return x;
    }

    private String classifyGdacsType(String probe) {
        if (probe.contains("cyclone") || probe.contains("hurricane") || probe.contains("typhoon") || probe.contains("storm")) return "STORM";
        if (probe.contains("volcano") || probe.contains("eruption")) return "VULKAAN";
        if (probe.contains("flood")) return "OVERSTROMING";
        if (probe.contains("wildfire") || probe.contains("forest fire")) return "BOSBRAND";
        if (probe.contains("tsunami")) return "TSUNAMI";
        return "RAMP";
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "DefconWatch-Android/2.3");
        c.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*");
        if (c.getResponseCode() >= 400) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }

    private void applyData(List<Incident> fetched, String errors) {
        refreshButton.setEnabled(true);
        progress.setVisibility(View.GONE);
        if (!fetched.isEmpty()) {
            incidents.clear();
            incidents.addAll(fetched);
            saveCache();
            statusText.setText(errors.isEmpty() ? "LIVE • alle openbare feeds gesynchroniseerd" : "DEELS LIVE • " + errors.trim());
            lastUpdated.setText("Laatste update: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()));
            maybeNotifyCritical(fetched);
        } else if (!incidents.isEmpty()) {
            statusText.setText("OFFLINE CACHE • live feeds niet bereikbaar");
        } else {
            statusText.setText("Geen gegevens beschikbaar. Controleer de internetverbinding.");
        }
        updateReadiness();
        updateRegions();
        renderIncidents();
        mapView.setIncidents(new ArrayList<>(incidents));
    }

    private void updateReadiness() {
        int max = 1;
        int high = 0;
        for (Incident i : incidents) { max = Math.max(max, i.severity); if (i.severity >= 4) high++; }
        int level = max >= 5 || high >= 3 ? 3 : max >= 4 || incidents.size() >= 8 ? 4 : 5;
        int color = level == 3 ? Color.rgb(255,78,85) : level == 4 ? Color.rgb(255,213,74) : Color.rgb(66,226,123);
        levelNumber.setText(String.valueOf(level));
        levelNumber.setTextColor(color);
        levelLabel.setText(level == 3 ? "HIGH PUBLIC ALERT\nUNOFFICIAL OSINT ESTIMATE" : level == 4 ? "ELEVATED AWARENESS\nUNOFFICIAL OSINT ESTIMATE" : "NORMAL AWARENESS\nUNOFFICIAL OSINT ESTIMATE");
    }

    private void updateRegions() {
        Map<String,Integer> counts = new HashMap<>();
        for (String key : regionViews.keySet()) counts.put(key, 0);
        for (Incident i : incidents) {
            String r = regionFor(i.lat, i.lon);
            counts.put(r, counts.get(r) + 1);
        }
        for (Map.Entry<String,TextView> e : regionViews.entrySet()) {
            int n = counts.get(e.getKey());
            e.getValue().setText(n + (n == 1 ? " actief signaal" : " actieve signalen"));
        }
    }

    private void renderIncidents() {
        incidentContainer.removeAllViews();
        int shown = 0;
        for (Incident i : incidents) {
            if (!matchesFilter(i)) continue;
            LinearLayout card = panel();
            TextView title = text(i.title, 13, Color.WHITE, true);
            String meta = severityLabel(i.severity) + " • " + i.type + " • " + i.source;
            TextView metaView = text(meta, 11, severityColor(i.severity), true);
            card.addView(title);
            card.addView(metaView);
            card.setClickable(true);
            card.setOnClickListener(v -> openUrl(i.url));
            incidentContainer.addView(card);
            shown++;
            if (shown >= 12) break;
        }
        if (shown == 0) incidentContainer.addView(text("Geen incidenten binnen dit filter.", 12, Color.rgb(143,166,181), false));
    }

    private boolean matchesFilter(Incident i) {
        if ("ALLES".equals(activeFilter)) return true;
        if ("KRITIEK".equals(activeFilter)) return i.severity >= 4;
        return activeFilter.equals(i.type);
    }


    private void showIncidentDialog(Incident incident) {
        if (incident == null) return;
        new AlertDialog.Builder(this)
                .setTitle(incident.title)
                .setMessage(severityLabel(incident.severity) + " • " + incident.type + " • " + incident.source)
                .setNegativeButton("Sluiten", null)
                .setPositiveButton("Open bron", (dialog, which) -> openUrl(incident.url))
                .show();
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) { }
    }

    private void saveCache() {
        try {
            JSONArray arr = new JSONArray();
            for (Incident i : incidents) arr.put(i.toJson());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(CACHE_KEY, arr.toString()).putLong(CACHE_TIME, System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    private void loadCachedData() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(CACHE_KEY, null);
        if (raw == null) return;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) incidents.add(Incident.fromJson(arr.getJSONObject(i)));
            long t = prefs.getLong(CACHE_TIME, 0);
            statusText.setText("OFFLINE CACHE • live synchronisatie volgt");
            lastUpdated.setText("Cache: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(t)));
            updateReadiness(); updateRegions(); renderIncidents(); mapView.setIncidents(new ArrayList<>(incidents));
        } catch (Exception ignored) { }
    }

    private void maybeNotifyCritical(List<Incident> fetched) {
        Incident critical = null;
        for (Incident i : fetched) if (i.severity >= 5) { critical = i; break; }
        if (critical == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(critical.url));
        PendingIntent pending = PendingIntent.getActivity(this, 21, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(this, CHANNEL_ID) : new android.app.Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("DefconWatch: kritiek openbaar incident")
                .setContentText(critical.title)
                .setAutoCancel(true)
                .setContentIntent(pending);
        nm.notify(2101, b.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Kritieke incidenten", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 210);
    }

    private String regionFor(double lat, double lon) {
        if (lat >= 15 && lon >= -170 && lon <= -50) return lat >= 12 ? "Noord-Amerika" : "Latijns-Amerika";
        if (lat < 15 && lon >= -120 && lon <= -30) return "Latijns-Amerika";
        if (lat >= 35 && lon >= -25 && lon <= 45) return "Europa";
        if (lat >= 12 && lat < 42 && lon >= 25 && lon <= 65) return "Midden-Oosten";
        if (lon >= -20 && lon <= 55 && lat < 37 && lat > -40) return "Afrika";
        return "Azië-Pacific";
    }

    private double[] roughCoordinates(String text) {
        if (text.contains("japan")) return new double[]{36,138};
        if (text.contains("indonesia")) return new double[]{-2,118};
        if (text.contains("philipp")) return new double[]{13,122};
        if (text.contains("mexico")) return new double[]{23,-102};
        if (text.contains("chile")) return new double[]{-30,-71};
        if (text.contains("turkey") || text.contains("türkiye")) return new double[]{39,35};
        if (text.contains("india")) return new double[]{22,79};
        if (text.contains("china")) return new double[]{35,104};
        if (text.contains("usa") || text.contains("united states")) return new double[]{38,-97};
        return new double[]{0,0};
    }

    private int severityColor(int s) { return s >= 5 ? Color.rgb(255,78,85) : s == 4 ? Color.rgb(255,152,56) : s == 3 ? Color.rgb(255,213,74) : Color.rgb(66,211,255); }
    private String severityLabel(int s) { return s >= 5 ? "KRITIEK" : s == 4 ? "HOOG" : s == 3 ? "VERHOOGD" : "INFORMATIEF"; }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    static class Incident {
        String type, title, source, url;
        double lat, lon;
        int severity;
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("type", type); o.put("title", title); o.put("source", source); o.put("url", url);
            o.put("lat", lat); o.put("lon", lon); o.put("severity", severity);
            return o;
        }
        static Incident fromJson(JSONObject o) {
            Incident i = new Incident();
            i.type=o.optString("type"); i.title=o.optString("title"); i.source=o.optString("source"); i.url=o.optString("url");
            i.lat=o.optDouble("lat"); i.lon=o.optDouble("lon"); i.severity=o.optInt("severity");
            return i;
        }
    }

    static class WorldMapView extends View {
        interface MarkerClickListener { void onMarkerClick(Incident incident); }
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Incident> points = new ArrayList<>();
        private MarkerClickListener markerClickListener;
        WorldMapView(Context c) { super(c); paint.setStrokeWidth(2f); setBackgroundColor(Color.rgb(8,14,20)); setClickable(true); }
        void setIncidents(List<Incident> x) { points.clear(); points.addAll(x); invalidate(); }
        void setMarkerClickListener(MarkerClickListener listener) { markerClickListener = listener; }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            Incident nearest = null;
            double best = Double.MAX_VALUE;
            for (Incident i : points) {
                float x=(float)((i.lon+180d)/360d*getWidth());
                float y=(float)((90d-i.lat)/180d*getHeight());
                double d = Math.hypot(event.getX()-x, event.getY()-y);
                if (d < best) { best = d; nearest = i; }
            }
            if (nearest != null && best <= 42d && markerClickListener != null) markerClickListener.onMarkerClick(nearest);
            return true;
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight();
            paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.rgb(45,77,96)); paint.setStrokeWidth(1f);
            for(int i=1;i<6;i++) c.drawLine(w*i/6f,0,w*i/6f,h,paint);
            for(int i=1;i<4;i++) c.drawLine(0,h*i/4f,w,h*i/4f,paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(23,50,64));
            Path p=new Path();
            p.moveTo(w*.07f,h*.28f); p.lineTo(w*.25f,h*.15f); p.lineTo(w*.34f,h*.32f); p.lineTo(w*.28f,h*.57f); p.lineTo(w*.17f,h*.63f); p.lineTo(w*.10f,h*.48f); p.close(); c.drawPath(p,paint);
            p.reset(); p.moveTo(w*.25f,h*.62f); p.lineTo(w*.34f,h*.55f); p.lineTo(w*.39f,h*.76f); p.lineTo(w*.31f,h*.94f); p.lineTo(w*.24f,h*.77f); p.close(); c.drawPath(p,paint);
            p.reset(); p.moveTo(w*.43f,h*.25f); p.lineTo(w*.58f,h*.16f); p.lineTo(w*.65f,h*.31f); p.lineTo(w*.58f,h*.43f); p.lineTo(w*.47f,h*.40f); p.close(); c.drawPath(p,paint);
            p.reset(); p.moveTo(w*.50f,h*.43f); p.lineTo(w*.66f,h*.36f); p.lineTo(w*.72f,h*.59f); p.lineTo(w*.62f,h*.87f); p.lineTo(w*.52f,h*.69f); p.close(); c.drawPath(p,paint);
            p.reset(); p.moveTo(w*.63f,h*.23f); p.lineTo(w*.91f,h*.20f); p.lineTo(w*.95f,h*.47f); p.lineTo(w*.77f,h*.58f); p.lineTo(w*.66f,h*.39f); p.close(); c.drawPath(p,paint);
            p.reset(); p.moveTo(w*.80f,h*.73f); p.lineTo(w*.92f,h*.70f); p.lineTo(w*.96f,h*.85f); p.lineTo(w*.85f,h*.92f); p.close(); c.drawPath(p,paint);
            for(Incident i: points) {
                float x=(float)((i.lon+180d)/360d*w); float y=(float)((90d-i.lat)/180d*h);
                paint.setColor("SPACE".equals(i.type)?Color.rgb(167,139,250):i.severity>=5?Color.rgb(255,78,85):i.severity==4?Color.rgb(255,152,56):i.severity==3?Color.rgb(255,213,74):Color.rgb(66,211,255));
                paint.setStyle(Paint.Style.FILL);
                if ("SPACE".equals(i.type)) c.drawRect(x-6f,y-6f,x+6f,y+6f,paint); else c.drawCircle(x,y,i.severity>=4?8f:5f,paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f); c.drawCircle(x,y,"SPACE".equals(i.type)?12f:(i.severity>=4?14f:9f),paint);
            }
        }
    }
}

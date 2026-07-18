package nl.defconwatch.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
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
    private static final long REFRESH_MS = 15L * 60L * 1000L;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Incident> incidents = new ArrayList<>();
    private final Map<String, TextView> regionViews = new HashMap<>();

    private WorldMapView mapView;
    private TextView levelNumber;
    private TextView levelLabel;
    private TextView statusText;
    private TextView lastUpdated;
    private TextView incidentList;
    private ProgressBar progress;
    private Button refreshButton;

    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            refreshData();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();
        requestNotificationPermission();
        setContentView(buildUi());
        refreshData();
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
        TextView title = text("DEFCONWATCH", 24, Color.rgb(237,245,250), true);
        TextView subtitle = text("PUBLIC OSINT COMMAND CENTER", 10, Color.rgb(66,211,255), true);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        refreshButton = new Button(this);
        refreshButton.setText("↻ LIVE");
        refreshButton.setTextColor(Color.BLACK);
        refreshButton.setBackgroundColor(Color.rgb(66,211,255));
        refreshButton.setOnClickListener(v -> refreshData());
        header.addView(refreshButton, new LinearLayout.LayoutParams(dp(96), dp(46)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(30));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout readiness = panel();
        TextView indexTitle = text("GLOBAL READINESS INDEX", 12, Color.rgb(143,166,181), true);
        readiness.addView(indexTitle);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        levelNumber = text("4", 72, Color.rgb(255,213,74), true);
        levelLabel = text("ELEVATED AWARENESS\nUNOFFICIAL OSINT ESTIMATE", 15, Color.WHITE, true);
        row.addView(levelNumber, new LinearLayout.LayoutParams(dp(100), dp(100)));
        row.addView(levelLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        readiness.addView(row);
        TextView disclaimer = text("Geen officiële of geclassificeerde DEFCON-feed. De index wordt uitsluitend afgeleid uit openbare incidentgegevens.", 11, Color.rgb(143,166,181), false);
        readiness.addView(disclaimer);
        content.addView(readiness);

        FrameLayout mapPanel = new FrameLayout(this);
        mapPanel.setBackgroundColor(Color.rgb(16,24,32));
        mapPanel.setPadding(dp(6), dp(6), dp(6), dp(6));
        mapView = new WorldMapView(this);
        mapPanel.addView(mapView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        progress = new ProgressBar(this);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER);
        mapPanel.addView(progress, pp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(272));
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
            TextView name = text(region, 14, Color.WHITE, true);
            TextView count = text("0 actieve signalen", 12, Color.rgb(143,166,181), false);
            r.addView(name);
            r.addView(count);
            regionViews.put(region, count);
            content.addView(r);
        }

        TextView incidentsTitle = text("LAATSTE INCIDENTEN", 13, Color.rgb(66,211,255), true);
        incidentsTitle.setPadding(0, dp(16), 0, dp(6));
        content.addView(incidentsTitle);
        LinearLayout incidentPanel = panel();
        incidentList = text("Nog geen incidenten geladen.", 12, Color.rgb(237,245,250), false);
        incidentPanel.addView(incidentList);
        content.addView(incidentPanel);

        TextView sources = text("LIVE BRONNEN: USGS • GDACS\nAutomatische verversing: iedere 15 minuten zolang de app geopend is.", 10, Color.rgb(143,166,181), false);
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

    private void refreshData() {
        refreshButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusText.setText("Live feeds worden opgehaald…");
        executor.execute(() -> {
            List<Incident> fetched = new ArrayList<>();
            StringBuilder errors = new StringBuilder();
            try { fetched.addAll(fetchUsgs()); } catch (Exception e) { errors.append("USGS niet bereikbaar. "); }
            try { fetched.addAll(fetchGdacs()); } catch (Exception e) { errors.append("GDACS niet bereikbaar. "); }
            String errorText = errors.toString();
            handler.post(() -> applyData(fetched, errorText));
        });
    }

    private List<Incident> fetchUsgs() throws Exception {
        HttpURLConnection connection = open(USGS_URL);
        String json;
        try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
            json = readAll(in);
        } finally { connection.disconnect(); }
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
            Incident current = null;
            String tag = "";
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT && result.size() < 20) {
                if (event == XmlPullParser.START_TAG) {
                    tag = parser.getName();
                    if ("item".equalsIgnoreCase(tag)) current = new Incident();
                } else if (event == XmlPullParser.TEXT && current != null) {
                    String value = parser.getText().trim();
                    if (!value.isEmpty()) {
                        if ("title".equalsIgnoreCase(tag)) current.title = value;
                        else if (tag.toLowerCase(Locale.ROOT).contains("point")) {
                            String[] parts = value.split("\\s+");
                            if (parts.length >= 2) { current.lat = parse(parts[0]); current.lon = parse(parts[1]); }
                        } else if (tag.toLowerCase(Locale.ROOT).contains("alertlevel")) {
                            current.severity = "Red".equalsIgnoreCase(value) ? 5 : "Orange".equalsIgnoreCase(value) ? 4 : 2;
                        } else if (tag.toLowerCase(Locale.ROOT).contains("eventtype")) current.type = value;
                    }
                } else if (event == XmlPullParser.END_TAG && "item".equalsIgnoreCase(parser.getName()) && current != null) {
                    if (TextUtils.isEmpty(current.type)) current.type = "RAMP";
                    if (current.severity == 0) current.severity = 2;
                    current.source = "GDACS";
                    if (!TextUtils.isEmpty(current.title)) result.add(current);
                    current = null;
                }
                event = parser.next();
            }
        } finally { connection.disconnect(); }
        return result;
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "DefconWatch-Android/2.0");
        c.setRequestProperty("Accept", "application/json, application/xml, text/xml, */*");
        if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }

    private void applyData(List<Incident> fetched, String errors) {
        incidents.clear();
        incidents.addAll(fetched);
        mapView.setIncidents(incidents);
        progress.setVisibility(View.GONE);
        refreshButton.setEnabled(true);
        int level = calculateLevel(incidents);
        levelNumber.setText(String.valueOf(level));
        levelNumber.setTextColor(levelColor(level));
        levelLabel.setText(levelLabel(level) + "\nUNOFFICIAL OSINT ESTIMATE");
        statusText.setText(incidents.size() + " openbare incidenten geladen" + (errors.isEmpty() ? "" : " • " + errors.trim()));
        lastUpdated.setText("Laatste synchronisatie: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()));
        updateRegions();
        updateIncidentList();
        maybeNotify();
    }

    private int calculateLevel(List<Incident> list) {
        int max = 0, severe = 0;
        for (Incident i : list) { max = Math.max(max, i.severity); if (i.severity >= 4) severe++; }
        if (max >= 5 && severe >= 3) return 2;
        if (max >= 5 || severe >= 2) return 3;
        if (!list.isEmpty()) return 4;
        return 5;
    }

    private String levelLabel(int level) {
        switch (level) {
            case 2: return "SEVERE GLOBAL SIGNALS";
            case 3: return "HEIGHTENED AWARENESS";
            case 4: return "ELEVATED AWARENESS";
            default: return "ROUTINE MONITORING";
        }
    }

    private int levelColor(int level) {
        if (level <= 2) return Color.rgb(255,78,85);
        if (level == 3) return Color.rgb(255,152,56);
        if (level == 4) return Color.rgb(255,213,74);
        return Color.rgb(66,226,123);
    }

    private void updateRegions() {
        Map<String,Integer> counts = new HashMap<>();
        for (String key : regionViews.keySet()) counts.put(key, 0);
        for (Incident i : incidents) {
            String r = regionFor(i.lat, i.lon);
            counts.put(r, counts.get(r) + 1);
        }
        for (Map.Entry<String, TextView> e : regionViews.entrySet()) {
            int count = counts.get(e.getKey());
            e.getValue().setText(count + (count == 1 ? " actief signaal" : " actieve signalen"));
            e.getValue().setTextColor(count > 0 ? Color.rgb(255,213,74) : Color.rgb(143,166,181));
        }
    }

    private String regionFor(double lat, double lon) {
        if (lon >= -25 && lon <= 45 && lat >= 35) return "Europa";
        if (lon < -30 && lat >= 15) return "Noord-Amerika";
        if (lon < -30) return "Latijns-Amerika";
        if (lon >= 25 && lon <= 65 && lat >= 10 && lat <= 42) return "Midden-Oosten";
        if (lon >= -20 && lon <= 55 && lat < 35) return "Afrika";
        return "Azië-Pacific";
    }

    private void updateIncidentList() {
        if (incidents.isEmpty()) { incidentList.setText("Geen actuele incidenten gevonden of feeds tijdelijk niet bereikbaar."); return; }
        incidents.sort((a,b) -> Integer.compare(b.severity, a.severity));
        StringBuilder text = new StringBuilder();
        int max = Math.min(12, incidents.size());
        for (int i = 0; i < max; i++) {
            Incident x = incidents.get(i);
            text.append(severityIcon(x.severity)).append(' ').append(x.type).append(" • ").append(x.source).append('\n')
                    .append(x.title).append("\n")
                    .append(String.format(Locale.US, "%.2f, %.2f", x.lat, x.lon)).append("\n\n");
        }
        incidentList.setText(text.toString().trim());
    }

    private String severityIcon(int s) { return s >= 5 ? "🔴" : s >= 4 ? "🟠" : s >= 3 ? "🟡" : "🔵"; }

    private void maybeNotify() {
        Incident highest = null;
        for (Incident i : incidents) if (highest == null || i.severity > highest.severity) highest = i;
        if (highest == null || highest.severity < 5) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        android.app.Notification notification = new android.app.Notification.Builder(this, "critical")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("DefconWatch: hoog incident")
                .setContentText(highest.title)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(highest.title + " • bron: " + highest.source))
                .setAutoCancel(true).build();
        nm.notify(2001, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("critical", "Hoge incidenten", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Waarschuwingen voor incidenten met de hoogste openbare ernstcategorie.");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private String readAll(InputStream in) throws Exception {
        byte[] buffer = new byte[8192];
        StringBuilder out = new StringBuilder();
        int n;
        while ((n = in.read(buffer)) >= 0) out.append(new String(buffer, 0, n, java.nio.charset.StandardCharsets.UTF_8));
        return out.toString();
    }

    private double parse(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    static class Incident {
        String type, title, source;
        double lat, lon;
        int severity;
    }

    static class WorldMapView extends View {
        private final Paint ocean = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint land = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Incident> points = new ArrayList<>();

        WorldMapView(Context c) {
            super(c);
            ocean.setColor(Color.rgb(7,18,27));
            grid.setColor(Color.rgb(27,57,72)); grid.setStrokeWidth(1f);
            land.setColor(Color.rgb(36,75,84)); land.setStyle(Paint.Style.FILL);
        }

        void setIncidents(List<Incident> data) { points.clear(); points.addAll(data); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            c.drawRoundRect(new RectF(0,0,w,h), 18,18,ocean);
            for (int lon=-120; lon<=120; lon+=60) { float x=(float)((lon+180)/360.0*w); c.drawLine(x,0,x,h,grid); }
            for (int lat=-60; lat<=60; lat+=30) { float y=(float)((90-lat)/180.0*h); c.drawLine(0,y,w,y,grid); }
            drawContinent(c,w,h,new double[][]{{-168,72},{-130,55},{-115,25},{-82,10},{-60,45},{-80,70}});
            drawContinent(c,w,h,new double[][]{{-82,10},{-70,-5},{-72,-50},{-52,-56},{-35,-10},{-50,5}});
            drawContinent(c,w,h,new double[][]{{-12,72},{30,72},{42,50},{25,35},{5,36},{-10,55}});
            drawContinent(c,w,h,new double[][]{{-18,35},{12,36},{48,12},{38,-35},{18,-35},{-5,5}});
            drawContinent(c,w,h,new double[][]{{30,72},{178,65},{150,10},{105,5},{68,25},{42,50}});
            drawContinent(c,w,h,new double[][]{{112,-10},{155,-12},{154,-42},{115,-38}});
            for (Incident i : points) {
                float x=(float)((i.lon+180)/360.0*w), y=(float)((90-i.lat)/180.0*h);
                marker.setColor(i.severity>=5?Color.rgb(255,78,85):i.severity>=4?Color.rgb(255,152,56):i.severity>=3?Color.rgb(255,213,74):Color.rgb(66,211,255));
                c.drawCircle(x,y,i.severity>=5?9:i.severity>=4?7:5,marker);
                marker.setStyle(Paint.Style.STROKE); marker.setStrokeWidth(2); c.drawCircle(x,y,i.severity>=5?15:10,marker); marker.setStyle(Paint.Style.FILL);
            }
        }

        private void drawContinent(Canvas c,float w,float h,double[][] coords) {
            Path p=new Path();
            for(int i=0;i<coords.length;i++) {
                float x=(float)((coords[i][0]+180)/360.0*w), y=(float)((90-coords[i][1])/180.0*h);
                if(i==0)p.moveTo(x,y);else p.lineTo(x,y);
            }
            p.close(); c.drawPath(p,land);
        }
    }
}
